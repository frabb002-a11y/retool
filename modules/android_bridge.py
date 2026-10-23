"""Headless driver used by the Android app.

The desktop GUI fills a ``UserInput`` object, writes ``user-config.yaml`` and the
per-system YAML files, then calls ``retool.main``. This module does the same work
from a JSON document so the filter itself stays unchanged.
"""

from __future__ import annotations

import html
import json
import os
import pathlib
import re
import sys
import threading
import traceback
from typing import Any

_lock = threading.Lock()
_listener: Any = None
_root: pathlib.Path | None = None
_config: Any = None

_ANSI = re.compile(r'\x1b\[[0-9;?]*[A-Za-z]|\x1bM')

_EXCLUDE_LETTERS = (
    ('D', 'no_add_ons'),
    ('a', 'no_applications'),
    ('A', 'no_audio'),
    ('b', 'no_bad_dumps'),
    ('B', 'no_bios'),
    ('o', 'no_bonus_discs'),
    ('c', 'no_coverdiscs'),
    ('d', 'no_demos'),
    ('e', 'no_educational'),
    ('f', 'no_aftermarket'),
    ('g', 'no_games'),
    ('m', 'no_manuals'),
    ('k', 'no_mia'),
    ('M', 'no_multimedia'),
    ('p', 'no_pirate'),
    ('P', 'no_preproduction'),
    ('r', 'no_promotional'),
    ('u', 'no_unlicensed'),
    ('v', 'no_video'),
)


class _Log:
    def write(self, text: str) -> int:
        if not text:
            return 0
        clean = _ANSI.sub('', text)
        if _listener is not None and clean.strip():
            try:
                _listener.onLog(clean)
            except Exception:
                pass
        return len(text)

    def flush(self) -> None:
        return None

    def isatty(self) -> bool:
        return False


def set_listener(listener: Any) -> None:
    """Receive log lines. ``listener.onLog(text)`` is called from the worker thread."""
    global _listener
    _listener = listener


def configure(root: str) -> None:
    """Point Retool at the app files directory and create the folders it expects."""
    global _root
    _root = pathlib.Path(root)
    os.environ['RETOOL_NONINTERACTIVE'] = '1'
    os.environ['RETOOL_FORCE_SINGLE_CPU'] = '1'
    sys.argv[0] = str(_root / 'retool.py')
    os.chdir(_root)
    for name in (
        'config',
        'config/systems',
        'clonelists',
        'metadata',
        'mias',
        'retroachievements',
        'inbox',
        'output',
    ):
        (_root / name).mkdir(parents=True, exist_ok=True)
    sys.stderr = _Log()


def _require_root() -> pathlib.Path:
    if _root is None:
        raise RuntimeError('configure() has not been called')
    config_file = _root / 'config' / 'internal-config.json'
    if not config_file.is_file():
        raise RuntimeError(f'Missing {config_file}')
    return _root


def _internal() -> dict[str, Any]:
    root = _require_root()
    with open(root / 'config' / 'internal-config.json', encoding='utf-8') as handle:
        loaded: dict[str, Any] = json.load(handle)
    return loaded


def catalog() -> str:
    """Regions, languages, and video standards from internal-config.json."""
    data = _internal()
    payload = {
        'regions': list(data['defaultRegionOrder'].keys()),
        'languages': list(data['languages'].keys()),
        'video': list(data['defaultVideoOrder']),
    }
    import modules.constants as const

    payload['version'] = const.__version__
    return json.dumps(payload)


def _make_config(user_input: Any) -> Any:
    import modules.constants as const
    from modules.config.config import Config

    return Config(
        const.CLONE_LIST_METADATA_DOWNLOAD_LOCATION,
        const.CLONE_LIST_METADATA_DOWNLOAD_LOCATION_KEY,
        const.PROGRAM_DOWNLOAD_LOCATION,
        const.PROGRAM_DOWNLOAD_LOCATION_KEY,
        const.CONFIG_FILE,
        const.DAT_FILE_TAGS_KEY,
        const.IGNORE_TAGS_KEY,
        const.DISC_RENAME_KEY,
        const.VERSION_IGNORE_KEY,
        const.BUDGET_EDITIONS_KEY,
        const.PROMOTE_EDITIONS_KEY,
        const.DEMOTE_EDITIONS_KEY,
        const.MODERN_EDITIONS_KEY,
        const.LANGUAGES_KEY,
        const.REGION_ORDER_KEY,
        const.VIDEO_ORDER_KEY,
        const.CLONE_LISTS_KEY,
        const.METADATA_KEY,
        const.MIAS_KEY,
        const.RA_KEY,
        const.USER_CONFIG_KEY,
        const.USER_LANGUAGE_ORDER_KEY,
        const.USER_REGION_ORDER_KEY,
        const.USER_LOCALIZATION_ORDER_KEY,
        const.USER_VIDEO_ORDER_KEY,
        const.USER_LIST_PREFIX_KEY,
        const.USER_LIST_SUFFIX_KEY,
        const.USER_OVERRIDE_EXCLUDE_KEY,
        const.USER_OVERRIDE_INCLUDE_KEY,
        const.USER_FILTER_KEY,
        const.USER_GUI_SETTINGS_KEY,
        const.SYSTEM_SETTINGS_PATH,
        const.SANITIZED_CHARACTERS,
        const.RESERVED_FILENAMES,
        user_input,
        first_run_gui=True,
    )


def _base_config() -> Any:
    global _config
    if _config is None:
        from modules.input import UserInput

        _config = _make_config(UserInput(single_cpu=True))
    return _config


def _system_name(dat_file_path: str) -> str:
    """Same header read as the desktop GUI, without importing PySide."""
    from modules.dat.parse_dat import format_system_name
    from modules.titletools import TitleTools
    from modules.utils import pattern2string

    config = _base_config()
    with open(pathlib.Path(dat_file_path), 'rb') as file:
        pos = 0
        first_line = file.readline()
        header = b''
        system_name = ''
        file.seek(0)

        if (
            b'<?xml version' in first_line
            or b'<!DOCTYPE datafile' in first_line
            or b'<datafile>"' in first_line
        ):
            while file.read(9) != b'</header>':
                pos += 1
                file.seek(pos, os.SEEK_SET)
            file.seek(0)
            header = file.read(pos + 9)
            system_name = pattern2string(
                re.compile('<name>(.*?)</name>'), str(header), group_number=1
            ).strip()
        elif b'clrmamepro' in first_line:
            while file.read(2) != b'\n)':
                pos += 1
                file.seek(pos, os.SEEK_SET)
            file.seek(0)
            header = file.read(pos - 2)
            system_name = pattern2string(
                re.compile('name (.*)[\r\n]'), header.decode('utf-8'), group_number=1
            )
            system_name = system_name.replace('"', '').strip()

        system_name = TitleTools.replace_invalid_characters(
            re.sub(' \\(Retool.*?\\)', '', system_name).replace('&amp;', '&'),
            config,
            is_header_detail=True,
        )
        system_url = ''
        system_homepage = ''
        system_comment = ''
        system_author = ''
        if b'<url>' in header:
            system_url = pattern2string(
                re.compile('<url>(.*?)</url>'), str(header), group_number=1
            ).strip()
        if b'<homepage>' in header:
            system_homepage = pattern2string(
                re.compile('<homepage>(.*?)</homepage>'), str(header), group_number=1
            ).strip()
        if b'<comment>' in header:
            system_comment = pattern2string(
                re.compile('<comment>(.*?)</comment>'), str(header), group_number=1
            ).strip()
        if b'author' in header:
            system_author = pattern2string(
                re.compile('author.*([rR]edump.org)'), str(header), group_number=1
            ).strip()
        system_name = format_system_name(
            system_name, config, system_url, system_homepage, system_comment, system_author
        )
    return system_name


def identify(path: str) -> str:
    """Return the system name Retool will use for per-system settings."""
    try:
        name = _system_name(path)
        return json.dumps({'ok': True, 'systemName': name})
    except Exception as exc:
        return json.dumps({'ok': False, 'error': str(exc), 'trace': traceback.format_exc()})


def title_names(title: str) -> str:
    """Clone-list name tool: short name, group name, and region-free name."""
    from modules.titletools import TitleTools

    config = _base_config()
    text = html.unescape(title)
    tags = {f'({part}' for part in text.split(' (')[1:]}
    return json.dumps(
        {
            'short': TitleTools.get_short_name(text, tags, config),
            'group': TitleTools.get_group_name(text, config),
            'regionFree': TitleTools.get_region_free_name(text, tags, config),
        }
    )


def _lines(value: str) -> list[str]:
    return [line.replace('\\', '\\\\').replace('"', '\\"') for line in value.splitlines() if line]


def _ordered(catalog_names: list[str], selected: list[str], english_first: bool) -> tuple[str, ...]:
    selected_set = set(selected)
    available = [name for name in catalog_names if name not in selected_set]
    if english_first and 'English' in available:
        available = ['English'] + [name for name in available if name != 'English']
    return tuple([f'Comment|{name}' for name in available] + list(selected))


def _video(catalog_video: list[str], selected: list[str]) -> tuple[str, ...]:
    ordered = list(selected)
    for name in catalog_video:
        if name not in ordered:
            ordered.append(name)
    return tuple(ordered)


def _option_tokens(profile: dict[str, Any], local_names: bool) -> set[str]:
    tokens: set[str] = set()
    if profile.get('disable1g1r'):
        tokens.add('d')
    if profile.get('preferRegions'):
        tokens.add('r')
    if profile.get('oldest'):
        tokens.add('o')
    if profile.get('preferRetro'):
        tokens.add('c')
    if profile.get('modern'):
        tokens.add('z')
    if profile.get('demoteUnl'):
        tokens.add('y')
    if profile.get('noOverrides'):
        tokens.add('nooverrides')
    if profile.get('compilations'):
        tokens.add(f'compilations: {profile["compilations"]}')
    if profile.get('splitRegions'):
        tokens.add('regionsplit')
    if profile.get('removesDat'):
        tokens.add('removesdat')
    if profile.get('replace'):
        tokens.add('replace')
    if profile.get('reprocess'):
        tokens.add('reprocess')
    if profile.get('report'):
        tokens.add('report')
    if profile.get('useMachine'):
        tokens.add('machine')
    if profile.get('labelMia'):
        tokens.add('labelmia')
    if profile.get('labelRetro'):
        tokens.add('labelretro')
    if profile.get('originalHeader'):
        tokens.add('originalheader')
    if profile.get('listNames'):
        tokens.add('listnames')
    if profile.get('warnings'):
        tokens.add('warnings')
    if profile.get('legacy'):
        tokens.add('legacy')
    tokens.add('singlecpu')
    if profile.get('trace'):
        tokens.add(f'trace: "{profile["trace"]}"')
    excludes = ''.join(sorted(str(profile.get('excludes') or ''), key=str.lower))
    space = ' ' if excludes else ''
    tokens.add(f'exclude:{space}{excludes}')
    if local_names:
        tokens.add('n')
    return tokens


def _filename_options(profile: dict[str, Any], local_names: bool) -> str:
    letters: list[str] = []
    if profile.get('disable1g1r'):
        letters.append('d')
    if profile.get('preferRegions'):
        letters.append('r')
    if profile.get('oldest'):
        letters.append('o')
    if profile.get('preferRetro'):
        letters.append('c')
    if profile.get('modern'):
        letters.append('z')
    if profile.get('demoteUnl'):
        letters.append('y')
    if profile.get('noOverrides'):
        letters.append('nooverrides')
    if profile.get('legacy'):
        letters.append('x')
    if local_names:
        letters.append('n')
    if not letters:
        return ''
    return f' (-{"".join(sorted(letters))})'


def _exclude_kwargs(excludes: str) -> dict[str, bool]:
    return {field: letter in excludes for letter, field in _EXCLUDE_LETTERS}


def _write_configs(job: dict[str, Any], data: dict[str, Any]) -> None:
    from modules.config.read_write_config import UserOverrides, generate_config

    root = _require_root()
    languages = list(data['languages'].keys())
    regions = list(data['defaultRegionOrder'].keys())
    video = list(data['defaultVideoOrder'])
    global_profile: dict[str, Any] = job['global']
    output_dir = str(job['outputDir'])
    metadata_url = str(job.get('metadataUrl') or '')

    gui_settings = _option_tokens(global_profile, bool(global_profile.get('localizations')))
    gui_settings.add(f'output: {output_dir}')
    gui_settings.add('singlecpu')
    if metadata_url:
        gui_settings.add(f'clone list metadata url: {metadata_url}')

    user_config = str(root / 'config' / 'user-config.yaml')
    systems_dir = str(root / 'config' / 'systems')
    generate_config(
        user_config,
        _ordered(languages, list(global_profile.get('languages') or []), True),
        _ordered(regions, list(global_profile.get('regions') or []), False),
        _ordered(languages, list(global_profile.get('localizations') or []), True),
        _video(video, list(global_profile.get('video') or [])),
        systems_dir,
        UserOverrides(_lines(str(global_profile.get('excludeText') or '')), _lines(str(global_profile.get('includeText') or ''))),
        UserOverrides(),
        _lines(str(global_profile.get('filtersText') or '')),
        [],
        str(global_profile.get('prefix') or '').replace('\\', '\\\\').replace('"', '\\"'),
        str(global_profile.get('suffix') or '').replace('\\', '\\\\').replace('"', '\\"'),
        gui_settings,
        overwrite=True,
    )

    systems: dict[str, Any] = job.get('systems') or {}
    for system_name, profile in systems.items():
        if not isinstance(profile, dict):
            continue
        override_status = {
            'exclusions': str(bool(profile.get('overrideExclusions'))),
            'languages': str(bool(profile.get('overrideLanguages'))),
            'localizations': str(bool(profile.get('overrideLocalizations'))),
            'options': str(bool(profile.get('overrideOptions'))),
            'paths': str(bool(profile.get('overridePaths'))),
            'post_filters': str(bool(profile.get('overridePostFilters'))),
            'regions': str(bool(profile.get('overrideRegions'))),
            'video': str(bool(profile.get('overrideVideo'))),
        }
        system_tokens = _option_tokens(profile, bool(profile.get('localizations')))
        paths = {
            'output': str(profile.get('outputPath') or ''),
            'clone list': str(profile.get('cloneListPath') or ''),
            'metadata file': str(profile.get('metadataPath') or ''),
            'mia file': str(profile.get('miaPath') or ''),
            'retroachievements file': str(profile.get('raPath') or ''),
        }
        safe_name = system_name.replace('/', '-').replace('\\', '-')
        generate_config(
            str(root / 'config' / 'systems' / f'{safe_name}.yaml'),
            _ordered(languages, list(profile.get('languages') or []), True),
            _ordered(regions, list(profile.get('regions') or []), False),
            _ordered(languages, list(profile.get('localizations') or []), True),
            _video(video, list(profile.get('video') or [])),
            systems_dir,
            UserOverrides(),
            UserOverrides(_lines(str(profile.get('excludeText') or '')), _lines(str(profile.get('includeText') or ''))),
            [],
            _lines(str(profile.get('filtersText') or '')),
            str(profile.get('prefix') or '').replace('\\', '\\\\').replace('"', '\\"'),
            str(profile.get('suffix') or '').replace('\\', '\\\\').replace('"', '\\"'),
            system_tokens,
            overwrite=True,
            system_config=True,
            system_paths=paths,
            override_status=override_status,
        )


def _snapshot(folders: list[pathlib.Path]) -> dict[str, int]:
    found: dict[str, int] = {}
    for folder in folders:
        if not folder.is_dir():
            continue
        for path in folder.rglob('*'):
            if path.is_file():
                found[str(path.resolve())] = path.stat().st_mtime_ns
    return found


def _user_input_for(job: dict[str, Any], dat_path: str, system_profile: dict[str, Any] | None) -> Any:
    from modules.input import UserInput

    profile: dict[str, Any] = job['global']
    local_names = bool(profile.get('localizations')) or bool(
        system_profile
        and system_profile.get('overrideLocalizations')
        and system_profile.get('localizations')
    )
    filter_languages = bool(profile.get('languages')) or bool(
        system_profile and system_profile.get('overrideLanguages') and system_profile.get('languages')
    )
    excludes = ''.join(sorted(str(profile.get('excludes') or ''), key=str.lower))
    flags = _exclude_kwargs(excludes)
    output_dir = str(job['outputDir'])
    metadata_url = str(job.get('metadataUrl') or '')
    return UserInput(
        input_file_name=dat_path,
        filter_languages=filter_languages,
        local_names=local_names,
        oldest=bool(profile.get('oldest')),
        retroachievements=bool(profile.get('preferRetro')),
        region_bias=bool(profile.get('preferRegions')),
        legacy=bool(profile.get('legacy')),
        demote_unl=bool(profile.get('demoteUnl')),
        modern=bool(profile.get('modern')),
        compilations=str(profile.get('compilations') or ''),
        no_overrides=bool(profile.get('noOverrides')),
        no_1g1r=bool(profile.get('disable1g1r')),
        list_names=bool(profile.get('listNames')),
        report=bool(profile.get('report')),
        machine_not_game=bool(profile.get('useMachine')),
        label_mia=bool(profile.get('labelMia')),
        label_retro=bool(profile.get('labelRetro')),
        original_header=bool(profile.get('originalHeader')),
        output_folder_name=output_dir,
        user_output_folder=output_dir,
        output_region_split=bool(profile.get('splitRegions')),
        output_remove_dat=bool(profile.get('removesDat')),
        replace=bool(profile.get('replace')),
        reprocess_dat=bool(profile.get('reprocess')),
        verbose=bool(profile.get('warnings')),
        warningpause=False,
        single_cpu=True,
        trace=str(profile.get('trace') or ''),
        excludes=excludes,
        dev_mode=False,
        user_options=_filename_options(profile, local_names),
        user_clone_list_metadata_download_location=metadata_url,
        test=False,
        **flags,
    )


def run(job_json: str) -> str:
    """Write configs and filter every DAT in the job. Returns JSON."""
    if not _lock.acquire(blocking=False):
        return json.dumps({'ok': False, 'error': 'Retool is already running'})
    try:
        root = _require_root()
        job: dict[str, Any] = json.loads(job_json)
        data = _internal()
        _write_configs(job, data)
        output_dir = pathlib.Path(str(job['outputDir']))
        output_dir.mkdir(parents=True, exist_ok=True)
        folders = [output_dir]
        for profile in (job.get('systems') or {}).values():
            if isinstance(profile, dict) and profile.get('outputPath'):
                folder = pathlib.Path(str(profile['outputPath']))
                folder.mkdir(parents=True, exist_ok=True)
                folders.append(folder)
        if (job.get('global') or {}).get('replace'):
            for entry in job.get('files') or []:
                folders.append(pathlib.Path(str(entry['path'])).parent)
        before = _snapshot(folders)

        import retool
        from modules.utils import ExitRetool

        produced: list[str] = []
        errors: list[str] = []
        for entry in job.get('files') or []:
            dat_path = str(entry['path'])
            system_name = str(entry.get('systemName') or '')
            system_profile = (job.get('systems') or {}).get(system_name)
            user_input = _user_input_for(job, dat_path, system_profile if isinstance(system_profile, dict) else None)
            try:
                retool.main(user_input)
            except ExitRetool:
                pass
            except SystemExit as exc:
                errors.append(f'{pathlib.Path(dat_path).name} exited ({exc.code})')
            except Exception:
                errors.append(traceback.format_exc())
        after = _snapshot(folders)
        for path, mtime in after.items():
            if before.get(path) != mtime:
                produced.append(path)
        return json.dumps(
            {
                'ok': not errors,
                'files': produced,
                'errors': errors,
                'outputDir': str(root / 'output'),
            }
        )
    except Exception:
        return json.dumps({'ok': False, 'error': traceback.format_exc(), 'files': []})
    finally:
        _lock.release()


def update_lists(metadata_url: str) -> str:
    """Download clone lists, metadata, MIA, and RetroAchievements files."""
    if not _lock.acquire(blocking=False):
        return json.dumps({'ok': False, 'error': 'Retool is already running'})
    try:
        import modules.constants as const
        from modules.clone_lists.update_clone_list_metadata import update_clonelists_metadata
        from modules.input import UserInput
        from modules.utils import ExitRetool

        url = metadata_url or const.CLONE_LIST_METADATA_DOWNLOAD_LOCATION
        user_input = UserInput(
            update=True,
            single_cpu=True,
            user_clone_list_metadata_download_location=url,
        )
        config = _make_config(user_input)
        try:
            update_clonelists_metadata(config, user_input, no_exit=True)
        except ExitRetool:
            pass
        return json.dumps({'ok': True})
    except Exception:
        return json.dumps({'ok': False, 'error': traceback.format_exc()})
    finally:
        _lock.release()
