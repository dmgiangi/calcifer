#!/usr/bin/env python3
"""Create a consistent hawkBit backup on the Home backup PVC."""

from datetime import datetime
import json
import re
import subprocess

CONTEXT = ['kubectl', '--context', 'calcifer-home', '-n', 'home-automation']
FLUX = ['flux', '--context', 'calcifer-home']
HELPER = 'hawkbit-local-backup-once'
IMAGE = 'openremote/hawkbit-update-server@sha256:0a8114d12b2b7bfa33def41bfac280abc20c5675f4f871f5501b2cdcf531525d'
BACKUP_NAME = re.compile(r'^hawkbit-(\d{8}T\d{6}Z)\.tar$')


def run(args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


def k(*args, **kwargs):
    return run(CONTEXT + list(args), **kwargs)


def expired_backups(names):
    """Keep the newest backup for seven days and four ISO weeks with backups."""
    backups = []
    for name in names:
        match = BACKUP_NAME.fullmatch(name)
        if match:
            try:
                backups.append((datetime.strptime(match.group(1), '%Y%m%dT%H%M%SZ'), name))
            except ValueError:
                continue
    backups.sort(reverse=True)
    days, weeks, keep = set(), set(), set()
    for stamp, name in backups:
        day, week = stamp.date(), stamp.isocalendar()[:2]
        if day not in days and len(days) < 7:
            days.add(day)
            keep.add(name)
        if week not in weeks and len(weeks) < 4:
            weeks.add(week)
            keep.add(name)
    return [name for _, name in backups if name not in keep]


def helper_pod():
    return {
        'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': HELPER},
        'spec': {
            'automountServiceAccountToken': False, 'restartPolicy': 'Never',
            'nodeSelector': {'kubernetes.io/hostname': 'calcifer-home'},
            'securityContext': {'runAsUser': 1001, 'runAsGroup': 1001, 'fsGroup': 1001, 'seccompProfile': {'type': 'RuntimeDefault'}},
            'containers': [{
                'name': 'helper', 'image': IMAGE, 'command': ['/bin/sh', '-c', 'sleep 1800'],
                'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
                'volumeMounts': [{'name': 'data', 'mountPath': '/source', 'readOnly': True}, {'name': 'backups', 'mountPath': '/backup'}],
            }],
            'volumes': [
                {'name': 'data', 'persistentVolumeClaim': {'claimName': 'hawkbit-data'}},
                {'name': 'backups', 'persistentVolumeClaim': {'claimName': 'hawkbit-backups'}},
            ],
        },
    }


def main():
    state = json.loads(run(['kubectl', '--context', 'calcifer-home', '-n', 'flux-system', 'get', 'kustomization', 'home-apps', '-o', 'json']).stdout)
    if state.get('spec', {}).get('suspend', False):
        raise SystemExit('home-apps is already suspended; refusing to change its state')
    replicas = json.loads(k('get', 'deployment', 'hawkbit', '-o', 'json').stdout)['spec']['replicas']
    if replicas != 1:
        raise SystemExit('hawkBit must have exactly one desired replica')
    suspended = False
    created = False
    try:
        # create is atomic, so concurrent operators cannot share the helper.
        k('create', '-f', '-', input=json.dumps(helper_pod()).encode())
        created = True
        k('wait', '--for=condition=Ready', 'pod/' + HELPER, '--timeout=120s')
        run(FLUX + ['suspend', 'kustomization', 'home-apps'])
        suspended = True
        k('scale', 'deployment/hawkbit', '--replicas=0')
        k('wait', '--for=delete', 'pod', '-l', 'app.kubernetes.io/name=hawkbit', '--timeout=120s')
        command = '''set -eu
umask 077
stamp=$(date -u +%Y%m%dT%H%M%SZ)
tmp=/backup/.backup-${stamp}.partial
final=/backup/hawkbit-${stamp}.tar
trap 'rm -f "$tmp"' EXIT
[ ! -e "$final" ]
tar cf "$tmp" -C /source .
tar tf "$tmp" >/dev/null
sha256sum "$tmp" | sed "s|$tmp|$final|" > "$final.sha256"
mv "$tmp" "$final"
if ! sha256sum -c "$final.sha256" >/dev/null; then
    rm -f "$final" "$final.sha256"
    exit 1
fi
echo "Backup archive verified and stored: $(basename "$final")"
'''
        print(k('exec', HELPER, '--', 'sh', '-ec', command).stdout.decode().strip(), flush=True)
        names = k('exec', HELPER, '--', 'ls', '-1', '/backup').stdout.decode().splitlines()
        expired = expired_backups(names)
        for name in expired:
            k('exec', HELPER, '--', 'rm', '-f', '--', '/backup/' + name, '/backup/' + name + '.sha256')
        print(f'Applied retention; removed {len(expired)} older archives.', flush=True)
    finally:
        try:
            if suspended:
                try:
                    k('scale', 'deployment/hawkbit', '--replicas=1')
                finally:
                    run(FLUX + ['resume', 'kustomization', 'home-apps'])
                k('rollout', 'status', 'deployment/hawkbit', '--timeout=180s')
        finally:
            if created:
                subprocess.run(CONTEXT + ['delete', 'pod', HELPER, '--ignore-not-found=true', '--wait=true'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print('Service resumed and temporary helper removed.', flush=True)


if __name__ == '__main__':
    main()
