#!/usr/bin/env python3
"""Create a consistent hawkBit backup on the Home backup PVC."""
import json,subprocess
ctx=['kubectl','--context','calcifer-home','-n','home-automation'];flux=['flux','--context','calcifer-home'];name='hawkbit-local-backup-once'
def run(args,**kw):return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw)
def k(*args,**kw):return run(ctx+list(args),**kw)
if subprocess.run(ctx+['get','pod',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:raise SystemExit('Backup helper already exists')
state=json.loads(run(['kubectl','--context','calcifer-home','-n','flux-system','get','kustomization','home-apps','-o','json']).stdout)
if state.get('spec',{}).get('suspend',False):raise SystemExit('home-apps is already suspended; refusing to change its state')
replicas=json.loads(k('get','deployment','hawkbit','-o','json').stdout)['spec']['replicas']
if replicas!=1:raise SystemExit('hawkBit must have exactly one desired replica')
pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':name},'spec':{'automountServiceAccountToken':False,'restartPolicy':'Never','nodeSelector':{'kubernetes.io/hostname':'calcifer-home'},'securityContext':{'runAsUser':1001,'runAsGroup':1001,'fsGroup':1001,'seccompProfile':{'type':'RuntimeDefault'}},'containers':[{'name':'helper','image':'openremote/hawkbit-update-server@sha256:0a8114d12b2b7bfa33def41bfac280abc20c5675f4f871f5501b2cdcf531525d','command':['/bin/sh','-c','sleep 1800'],'securityContext':{'allowPrivilegeEscalation':False,'readOnlyRootFilesystem':True,'capabilities':{'drop':['ALL']}},'volumeMounts':[{'name':'data','mountPath':'/source','readOnly':True},{'name':'backups','mountPath':'/backup'}]}],'volumes':[{'name':'data','persistentVolumeClaim':{'claimName':'hawkbit-data'}},{'name':'backups','persistentVolumeClaim':{'claimName':'hawkbit-backups'}}]}}
suspended=False
try:
 k('apply','-f','-',input=json.dumps(pod).encode());k('wait','--for=condition=Ready','pod/'+name,'--timeout=120s')
 run(flux+['suspend','kustomization','home-apps']);suspended=True
 k('scale','deployment/hawkbit','--replicas=0')
 k('wait','--for=delete','pod','-l','app.kubernetes.io/name=hawkbit','--timeout=120s')
 cmd='set -eu; umask 077; stamp=$(date -u +%Y%m%dT%H%M%SZ); tmp=/backup/.backup-${stamp}.partial; final=/backup/hawkbit-${stamp}.tar; trap "rm -f $tmp" EXIT; tar cf "$tmp" -C /source .; tar tf "$tmp" >/dev/null; mv "$tmp" "$final"; sha256sum "$final" > "$final.sha256"; echo "Backup archive verified and stored: $(basename "$final")"'
 print(k('exec',name,'--','sh','-ec',cmd).stdout.decode().strip(),flush=True)
finally:
 try:
  if suspended:
   try:k('scale','deployment/hawkbit','--replicas=1')
   finally:run(flux+['resume','kustomization','home-apps'])
   k('rollout','status','deployment/hawkbit','--timeout=180s')
 finally:
  subprocess.run(ctx+['delete','pod',name,'--ignore-not-found=true','--wait=true'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 print('Service resumed and temporary helper removed.',flush=True)
