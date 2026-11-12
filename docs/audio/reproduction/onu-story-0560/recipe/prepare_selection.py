"""Stage explicit root-reviewed choices from complete take2 and retry4 take3.
No generation, source application, build, ADB, or automatic acoustic-quality verdict.
"""
from __future__ import annotations
import argparse,copy,hashlib,json,math,re,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5]
HERE=ROOT/'.local/onu-quality-0560/integration/revisions/mixed-selection-v2'
OLD=HERE.parents[1]/'prepare_integration.py'
OLD_SHA='984c196cf85aac0b9bc13cbe06a875f28616b1723e799e402022923207c190ad'
RETRY_PLAN=ROOT/'.local/onu-quality-0560/audio/retry-4-plan.json'
RETRY_PLAN_SHA='187edf6600a35134da2991ee2572260499512fce2c9105e6e078fe09e888e51e'
BASE_PROVENANCE_SHA='a333333dbca815eba10db46e3c42a74a07b063f5c2fe722ee60ab40402eed863'
ALLOWED={'dino_6d1af0571fbb38aa','dino_823088a509da2235','onu_8a2aa0dc157d243e','onu_a29b4330cfc58198'}
REPRO='docs/audio/reproduction/onu-story-0560'
ASSET_PREFIX='apps/vehicle/app/src/main/assets/'

def need(ok,message):
    if not ok: raise ValueError(message)

def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

need(sha(OLD)==OLD_SHA,'Original integration verifier changed')
p=types.ModuleType('original_integration_verifier');p.__file__=str(OLD)
exec(compile(OLD.read_bytes(),str(OLD),'exec'),p.__dict__)

def retry_source(base_plan,base_report,expected_provenance_sha):
    need(sha(RETRY_PLAN)==RETRY_PLAN_SHA,'Retry plan changed')
    plan=p.read(RETRY_PLAN);folder=p.AUDIO/'take-3'
    need(sha(folder/'provenance.json')==expected_provenance_sha,'Retry provenance changed')
    report=p.read(folder/'provenance.json');manifest=p.read(folder/'assets/manifest.json')
    for key in ('model','reference','api','processing'):
        need(plan[key]==base_plan[key],'Retry recipe changed beyond seed/subset: '+key)
    need(report['status']=='synthesized_not_listening_approved' and report['plan_sha256']==RETRY_PLAN_SHA,'Retry incomplete or wrong plan')
    need(report['model']==plan['model'] and report['reference']==plan['reference'],'Retry recipe/reference mismatch')
    need(report['manifest_sha256']==sha(folder/'assets/manifest.json'),'Retry manifest changed')
    need(manifest['schema_version']==1 and manifest['cues']==plan['cues']==base_plan['cues'],'Retry cues/schema changed')
    base=p.index(base_plan['clips'],'base plan');base_rows=p.index(base_report['clips'],'base output')
    planned=p.index(plan['clips'],'retry plan');rows=p.index(report['clips'],'retry provenance');clips=p.index(manifest['clips'],'retry manifest')
    need(set(planned)==set(rows)==set(clips)==ALLOWED,'Expected exact four retry candidates')
    for ident,row in planned.items():
        output,prov=clips[ident],rows[ident]
        need(p.same_identity(row,base[ident]) and p.same_identity(output,row) and p.same_identity(prov,row),'Retry identity changed: '+ident)
        need(row['expected_audio_sha256']==base_rows[ident]['sha256'],'Retry does not point to prior take2 candidate')
        need(row['seed']!=base[ident]['seed'] and prov['seed']==row['seed'],'Retry seed mismatch')
        need(all(prov.get(k)==v for k,v in output.items()),'Retry output/provenance differs')
        name=p.safe_asset(row['asset']);processed=folder/'assets'/name;raw=folder/'raw'/name
        need(sha(processed)==output['sha256'] and sha(raw)==prov['raw_sha256'],'Retry bytes changed')
        a,b=p.wav_facts(processed,False),p.wav_facts(raw,True)
        need(a['frames']==b['frames']==prov['frames'] and a['duration_ms']==output['duration_ms'] and prov['sample_rate']==24000,'Retry duration/rate/frame mismatch')
        need(prov['termination']['normal_end'] is True,'Retry did not end normally')
        need(all(math.isfinite(prov[k]) for k in ('integrated_lufs','true_peak_dbtp','static_gain_db')) and prov['true_peak_dbtp']<=-3.0,'Retry reported level invalid')
    for cue in plan['cues']:
        need(sha(folder/'assets'/p.safe_asset(cue['asset']))==cue['sha256'],'Retry cue bytes changed')
    return folder,plan,manifest,report

def choices(path,pin):
    need(sha(path)==pin,'Choice document changed')
    value=p.read(path)
    need(value['schema_version']==1 and value['status']=='selected_after_comparison','Root selection is not finalized')
    need(value['base_take']=='take-2' and value['retry_take']=='take-3','Unexpected take selection')
    selected=value['selected_retry_ids'];need(1<=len(selected)<=4 and len(set(selected))==len(selected) and set(selected)<=ALLOWED,'Select 1-4 explicit retry IDs; no retries selected means keep original single-take helper')
    need(set(value['rationale_by_id'])==set(selected) and all(isinstance(x,str) and len(x.strip())>=10 for x in value['rationale_by_id'].values()),'Each actual replacement needs its comparison rationale')
    need(value['human_listening_verified'] is False,'This helper cannot claim human listening approval')
    evidence=value['comparison_evidence'];need(isinstance(evidence,list) and len(evidence)>=2,'Provide pinned base and retry comparison evidence')
    for row in evidence:
        ep=Path(row['path']);need(ep.resolve().is_relative_to((ROOT/'.local/onu-quality-0560').resolve()),'Comparison evidence escaped0560')
        need(ep.is_file() and sha(ep)==row['sha256'],'Comparison evidence changed')
    return value,set(selected)

def stage(args):
    need(args.execute,'Explicit --execute stages only a NEW v2 review directory')
    need(re.fullmatch(r'review-[1-9][0-9]*',args.name),'Use review-N')
    dest=HERE/args.name;need(not dest.exists() and dest.resolve().parent==HERE.resolve(),'New stage directory required')
    plan,inv,old,before=p.baseline()
    need(sha(p.AUDIO/'take-2/provenance.json')==BASE_PROVENANCE_SHA,'Pinned base output changed')
    base_folder,base_manifest,base_report,base_checks=p.generated(plan,'take-2')
    retry_folder,retry_plan,retry_manifest,retry_report=retry_source(plan,base_report,args.retry_provenance_sha256)
    choice,picks=choices(args.choices,args.choices_sha256)
    source_data={'take-2':(base_folder,p.AUDIO/'plan.json',plan,base_manifest,base_report),
                 'take-3':(retry_folder,RETRY_PLAN,retry_plan,retry_manifest,retry_report)}
    sources=[]
    for name,(folder,plan_path,source_plan,manifest,report) in source_data.items():
        sources.append({'take':name,'plan_sha256':sha(plan_path),'provenance_sha256':sha(folder/'provenance.json'),
            'manifest_sha256':sha(folder/'assets/manifest.json'),'generated_clip_count':len(manifest['clips'])})
    selected_manifest={'schema_version':1,'clips':[],'cues':copy.deepcopy(plan['cues'])}
    selected_plan=copy.deepcopy(plan)
    selected_plan.update(status='explicit_mixed_selection_not_generation_or_listening_approval',base_plan_sha256=p.PLAN_SHA,
        seed_policy={'method':'Per-clip original source plan seed; root-selected take2/take3 candidates'},source_takes=sources)
    provenance={'schema_version':2,'version':'0.56.0','status':'staged_mixed_selection_not_integrated_or_listening_approved',
        'base_plan_sha256':p.PLAN_SHA,'model':plan['model'],'reference':plan['reference'],'processing':plan['processing'],
        'source_takes':sources,'selection_sha256':args.choices_sha256,'selection':choice,'clips':[],
        'human_listening_verified':False,'complete_voice_unification':False,'remaining':plan['remaining'],
        'source_batch_generation_not_claimed':True,'preserved_audio_count':61,'preserved_audio':inv['preserved_audio']}
    selected_plan['clips']=[]
    for row in plan['clips']:
        ident=row['id'];take='take-3' if ident in picks else 'take-2'
        folder,plan_path,source_plan,manifest,report=source_data[take]
        clip=copy.deepcopy(p.index(manifest['clips'],'source clips')[ident]);output=copy.deepcopy(p.index(report['clips'],'source report')[ident])
        source_spec=p.index(source_plan['clips'],'source spec')[ident]
        binding={'source_take':take,'source_plan_sha256':sha(plan_path),'source_provenance_sha256':sha(folder/'provenance.json'),
            'source_manifest_sha256':sha(folder/'assets/manifest.json'),'source_plan_asset':REPRO+'/sources/'+take+'/plan.json',
            'source_provenance_asset':REPRO+'/sources/'+take+'/provenance.json'}
        selected_manifest['clips'].append(clip)
        selected_plan['clips'].append({**copy.deepcopy(row),'seed':source_spec['seed'],
            'generation_expected_audio_sha256':source_spec['expected_audio_sha256'],**binding})
        output.update(**binding,old_sha256=row['expected_audio_sha256'],raw_asset=REPRO+'/raw/'+p.safe_asset(row['asset']),
            pitch_or_time_processing=False,selection_reason=choice['rationale_by_id'].get(ident,'Retained completed take2 candidate; no retry chosen for this ID'))
        provenance['clips'].append(output)
    dest.mkdir();package=dest/'package-root';copies=[];changed=[]
    for row in provenance['clips']:
        folder=source_data[row['source_take']][0];name=p.safe_asset(row['asset']);relative=ASSET_PREFIX+row['asset']
        p.copy_exact(folder/'assets'/name,package/relative,copies)
        p.copy_exact(folder/'raw'/name,package/row['raw_asset'],copies)
        changed.append({'path':relative,'old_sha256':row['old_sha256'],'new_sha256':row['sha256']})
    merged=copy.deepcopy(old);new=p.index(selected_manifest['clips'],'selection')
    for row in merged['clips']:
        if row['id'] in new: row.update({k:new[row['id']][k] for k in ('sha256','duration_ms')})
    manifest_relative=ASSET_PREFIX+'story_audio/manifest.json';p.write(package/manifest_relative,merged)
    changed.append({'path':manifest_relative,'old_sha256':sha(p.MANIFEST),'new_sha256':sha(package/manifest_relative)})
    p.write(package/'docs/audio/onu-0.56.0-voice-plan.json',selected_plan)
    provenance['selected_plan_sha256']=sha(package/'docs/audio/onu-0.56.0-voice-plan.json')
    provenance['merged_manifest_sha256']=sha(package/manifest_relative)
    p.write(package/'docs/audio/onu-0.56.0-voice-provenance.json',provenance)
    p.write(package/REPRO/'selected-manifest-52.json',selected_manifest)
    for source,relative in ((args.choices,REPRO+'/selection.json'),(p.AUDIO/'old-audio-inventory.json',REPRO+'/baseline-inventory.json'),
        (ROOT/'tools/build_qwen_voice.py',REPRO+'/recipe/build_qwen_voice.py'),(Path(__file__),REPRO+'/recipe/prepare_selection.py'),
        (ROOT/'docs/audio/reproduction/onu-letter-boy/reference.wav',REPRO+'/reference.wav'),
        (ROOT/'.local/qwen-voice-2026-11-05/models/Base/download-manifest.json',REPRO+'/model-download-manifest.json')):
        p.copy_exact(source,package/relative,copies)
    for name,(folder,plan_path,_,_,_) in source_data.items():
        for source,filename in ((plan_path,'plan.json'),(folder/'provenance.json','provenance.json'),(folder/'assets/manifest.json','manifest.json')):
            p.copy_exact(source,package/REPRO/'sources'/name/filename,copies)
    need(before==p.protected_snapshot(inv),'Protected production inputs changed while staging')
    records=[{'path':f.relative_to(package).as_posix(),'sha256':sha(f),'bytes':f.stat().st_size} for f in sorted(package.rglob('*')) if f.is_file()]
    need(len(records)==120 and sum(x['path'].startswith('docs/') for x in records)==67,'Unexpected mixed reproduction inventory')
    result={'schema_version':2,'status':'mixed_staged_only_pending_integration','plan_sha256':p.PLAN_SHA,'inventory_sha256':p.INVENTORY_SHA,
        'selected_plan_sha256':provenance['selected_plan_sha256'],'selection_sha256':args.choices_sha256,'source_takes':sources,
        'selected_retry_ids':sorted(picks),'retained_base_count':52-len(picks),'replacement_count':52,'preserved_audio_count':61,
        'raw_output_count':52,'reproduction_file_count':67,'expected_source_inputs':769,'source_assets_modified':False,
        'protected_inputs_before_after_identical':True,'protected_inputs':before,'changed_existing_targets':changed,
        'package_files':records,'exact_copies':copies,'remaining':plan['remaining'],'complete_voice_unification':False,
        'human_listening_verified':False,'generation_executed':False,'unselected_raw_outputs':'Retained in original take2/take3 folders, not copied into the selected raw52 bundle.'}
    p.write(dest/'result.json',result)
    print(json.dumps({'status':result['status'],'stage':dest.as_posix(),'result_sha256':sha(dest/'result.json'),
        'picked':sorted(picks),'package_files':len(records),'source_inputs':769},ensure_ascii=False))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--choices',type=Path,required=True);parser.add_argument('--choices-sha256',required=True)
    parser.add_argument('--retry-provenance-sha256',required=True);parser.add_argument('--name',default='review-1');parser.add_argument('--execute',action='store_true')
    stage(parser.parse_args())
if __name__=='__main__':main()
