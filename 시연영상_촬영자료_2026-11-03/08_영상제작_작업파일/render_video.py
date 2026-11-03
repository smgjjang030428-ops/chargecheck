from pathlib import Path
import json, math, wave, subprocess, shutil, re
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageDraw, ImageFont

W=Path(__file__).resolve().parent
BASE=W.parent
OUT=BASE/'07_최종_편집본'
RAW=BASE/'06_촬영한_원본영상'
FF=(W/'ffmpeg_path.txt').read_text(encoding='utf-8').strip()
BG=(17,19,21,255); WHITE=(242,243,244,255); MUTED=(177,185,193,255)
FONT=r'C:\Windows\Fonts\malgun.ttf'
BOLD=r'C:\Windows\Fonts\malgunbd.ttf'
ASSETS=W/'edit_assets'; ASSETS.mkdir(exist_ok=True)
CLIPS=W/'edited_chapters'; CLIPS.mkdir(exist_ok=True)
chapters=json.loads((W/'chapters.json').read_text(encoding='utf-8'))
events=json.loads((W/'recording_events.json').read_text(encoding='utf-8'))

def font(size,bold=False): return ImageFont.truetype(BOLD if bold else FONT,size)
def lines(text,size,width,bold=False):
    f=font(size,bold); result=[]
    for para in text.split('\n'):
        current=''
        for c in para:
            if current and f.getlength(current+c)>width:
                result.append(current.rstrip());current=c.lstrip()
            else: current+=c
        result.append(current)
    return result

def write_text(draw,xy,text,size,width,bold=False,color=WHITE,spacing=1.4):
    x,y=xy
    for line in lines(text,size,width,bold):
        draw.text((x,y),line,font=font(size,bold),fill=color,anchor='lt')
        y+=round(size*spacing)
    return y

def timecode(t):
    ms=round(t*1000);h,ms=divmod(ms,3600000);m,ms=divmod(ms,60000);s,ms=divmod(ms,1000)
    return f'{h:02}:{m:02}:{s:02},{ms:03}'

subtitles=[]; timeline=[]; cursor=0
for idx,c in enumerate(chapters):
    audio=[]; cursor_local=.8; params=None; cues=[]
    for i,sentence in enumerate(c['speech']):
        with wave.open(str(W/'speech'/f'{c["id"]}_{i}.wav'),'rb') as wav:
            if params is None: params=wav.getparams()
            assert wav.getnchannels()==params.nchannels and wav.getsampwidth()==params.sampwidth and wav.getframerate()==params.framerate
            data=wav.readframes(wav.getnframes());duration=wav.getnframes()/wav.getframerate()
        audio.append((cursor_local,data))
        cues.append((cursor_local,cursor_local+duration,sentence))
        cursor_local+=duration+.45
    duration=math.ceil(max(c['duration'],cursor_local+.35)*30)/30
    c['actual_duration']=duration;c['start']=cursor;c['cues']=cues
    sample_bytes=params.nchannels*params.sampwidth
    result=bytearray(math.ceil(duration*params.framerate)*sample_bytes)
    for start,data in audio:
        offset=round(start*params.framerate)*sample_bytes
        result[offset:offset+len(data)]=data
    with wave.open(str(ASSETS/(c['id']+'.wav')),'wb') as wav:
        wav.setnchannels(params.nchannels);wav.setsampwidth(params.sampwidth);wav.setframerate(params.framerate);wav.writeframes(result)
    image=Image.new('RGBA',(1920,1080),BG);d=ImageDraw.Draw(image)
    d.text((80,44),c['kicker'],font=font(25,True),fill=MUTED,anchor='lt')
    label_size=24
    while font(label_size).getlength(c['label'])>850: label_size-=1
    d.text((1840,45),c['label'],font=font(label_size),fill=MUTED,anchor='rt')
    d.line((80,104,1840,104),fill=(67,73,79),width=1)
    d.line((80,968,1840,968),fill=(67,73,79),width=1)
    d.text((1840,932),f'{idx+1:02} / {len(chapters):02}',font=font(20),fill=MUTED,anchor='rt')
    if c['id'] in ['realwarn','realswitch']:
        y=write_text(d,(1000,140),c['title'],48,840,True,spacing=1.32)
        y=748
        for line in c['lines']:
            y=write_text(d,(1000,y),line,27,840,color=MUTED,spacing=1.4)+8
    elif c['id'] in ['evidence','parked']:
        y=write_text(d,(88,174),c['title'],64,1750,True,spacing=1.36)
        for j,line in enumerate(c['lines']):
            x=88+j*590
            d.rounded_rectangle((x,480,x+558,775),radius=18,fill=(30,33,36),outline=(63,69,75),width=1)
            d.text((x+30,513),f'0{j+1}',font=font(25,True),fill=MUTED,anchor='lt')
            write_text(d,(x+30,575),line,36,495,True,spacing=1.5)
        if c['id']=='evidence':
            d.text((90,835),'최근 확인은 현재 빈자리·미래 충전 성공을 보장하지 않습니다.',font=font(27),fill=MUTED,anchor='lt')
    else:
        title_size=76 if c['id'] in ['intro','outro'] else 53
        while any(font(title_size,True).getlength(line)>650 for line in c['title'].split('\n')):
            title_size-=1
        y=write_text(d,(88,248),c['title'],title_size,650,True,spacing=1.4)
        y+=54
        for line in c['lines']:
            y=write_text(d,(92,y),line,29,620,color=MUTED,spacing=1.5)+18
        if c['id']=='intro':
            d.text((90,173),'차량의 충전 결과를 다음 운전자에게',font=font(25),fill=MUTED,anchor='lt')
        if c['id']=='outro':
            d.text((92,760),'현대자동차그룹 차량용 앱 개발 공모전',font=font(25),fill=MUTED,anchor='lt')
    image.save(ASSETS/(c['id']+'_bg.png'))
    for i,(start,end,text) in enumerate(cues):
        caption=Image.new('RGBA',(1920,1080),(0,0,0,0));cd=ImageDraw.Draw(caption)
        wrapped=lines(text,32,1710)
        assert len(wrapped)<=2,(c['id'],text)
        caption_y=996 if len(wrapped)==1 else 983
        for line in wrapped:
            cd.text((960,caption_y),line,font=font(32),fill=WHITE,anchor='mt')
            caption_y+=43
        caption.save(ASSETS/(c['id']+f'_caption_{i}.png'))
        subtitles.append((cursor+start,cursor+end,text))
    timeline.append({'id':c['id'],'start':cursor,'duration':duration,'label':c['label']})
    cursor+=duration

def raw(prefix): return next(RAW.glob(prefix+'*.mp4'))
real=raw('01_'); success=raw('02_'); failure=raw('03_'); realnav=raw('05_')
real_still=BASE/'02_최신_화면/01_release_상황카드와_보조지도.png'

def render(c):
    name=c['id'];duration=c['actual_duration']
    cmd=[FF,'-y','-hide_banner','-loglevel','error','-threads','3','-loop','1','-framerate','30','-i',str(ASSETS/(name+'_bg.png'))]
    spec=None
    if name in ['intro','outro']:
        spec=(real_still,True,0,(0,90,2560,1210),(1050,497),(790,265))
    elif name=='overview': spec=(real,False,0,(640,68,1260,906),(1074,772),(760,150))
    elif name=='success': spec=(success,False,4.3,(1008,86,486,570),(680,798),(1050,140))
    elif name=='failure': spec=(failure,False,29,(640,68,1260,906),(1074,772),(760,150))
    # 실제 release 녹화: 내비 영역(경고 카드·안내 패널)만 잘라 크게. 경로 미리보기를 5초 붙잡아 내레이션과 맞춘다
    elif name=='realwarn': spec=(realnav,False,35.5,(860,0,1300,1300),(850,850),(90,111),5)
    elif name=='realswitch': spec=(realnav,False,53.5,(860,0,1300,1300),(850,850),(90,111))
    filters=[]
    if spec:
        file,still,ss,crop,scale,pos,*hold=spec
        if still: cmd+=['-loop','1','-framerate','30']
        elif ss: cmd+=['-ss',str(ss)]
        cmd+=['-i',str(file)]
        x,y,w,h=crop;sw,sh=scale;px,py=pos
        pad=f'tpad=start_mode=clone:start_duration={hold[0]},' if hold else ''
        filters.append(f'[1:v]setpts=PTS-STARTPTS,fps=30,crop={w}:{h}:{x}:{y},scale={sw}:{sh}:flags=lanczos,{pad}tpad=stop_mode=clone:stop_duration={duration},setsar=1[media]')
        if name in ['realwarn','realswitch']:
            # 같은 녹화에서 내비 위 카드 영역(원본 900,20 / 760x380)을 1.1배로 따로 키워 오른쪽에 둔다
            filters[-1]=filters[-1].replace(f'crop={w}:{h}:{x}:{y},scale={sw}:{sh}:flags=lanczos,','').replace('[media]','[src];[src]split[s1][s2];[s1]'+f'crop={w}:{h}:{x}:{y},scale={sw}:{sh}:flags=lanczos[media];[s2]crop=760:380:900:20,scale=836:418:flags=lanczos[inset]')
            filters.append(f'[0:v][media]overlay={px}:{py}:eof_action=repeat[m0]')
            filters.append('[m0]drawbox=x=998:y=298:w=840:h=422:color=0x434A50:t=2[m1]')
            filters.append('[m1][inset]overlay=1000:300:eof_action=repeat[v0]')
        else:
            filters.append(f'[0:v][media]overlay={px}:{py}:eof_action=repeat[v0]')
        audio_idx=2
    else:
        filters.append('[0:v]null[v0]');audio_idx=1
    cmd+=['-i',str(ASSETS/(name+'.wav'))]
    for i in range(len(c['cues'])):
        cmd+=['-loop','1','-framerate','30','-i',str(ASSETS/(name+f'_caption_{i}.png'))]
    for i,(start,end,text) in enumerate(c['cues']):
        inp=audio_idx+1+i
        filters.append(f'[v{i}][{inp}:v]overlay=0:0:enable=\'between(t,{start:.4f},{end:.4f})\'[v{i+1}]')
    n=len(c['cues'])
    filters.append(f'[v{n}]fade=t=in:st=0:d=0.22,fade=t=out:st={duration-.22}:d=0.22,format=yuv420p[vout]')
    cmd+=['-filter_complex_threads','1','-filter_complex',';'.join(filters),'-map','[vout]','-map',f'{audio_idx}:a','-t',str(duration),'-r','30','-c:v','libx264','-preset','fast','-crf','19','-threads','3','-c:a','aac','-b:a','144k','-ar','48000','-ac','2','-movflags','+faststart',str(CLIPS/(name+'.mp4'))]
    with (W/(name+'_encode.log')).open('wb') as log:
        result=subprocess.run(cmd,stdout=log,stderr=log)
    if result.returncode: raise RuntimeError(name+' encoding failed: '+(W/(name+'_encode.log')).read_text(encoding='utf-8',errors='replace')[-2500:])
    print('Rendered: '+name,flush=True)

if __name__=='__main__':
    import sys
    selected=chapters
    if '--only' in sys.argv:
        names=sys.argv[sys.argv.index('--only')+1].split(',')
        selected=[c for c in chapters if c['id'] in names]
    with ThreadPoolExecutor(max_workers=2) as pool: list(pool.map(render,selected))
    concat=CLIPS/'concat.txt'
    concat.write_text('\n'.join("file '"+c['id']+".mp4'" for c in chapters),encoding='utf-8')
    final=OUT/'ChargeCheck_시연영상_한국어음성_1080p_2026-11-03.mp4'
    with (W/'final_encode.log').open('wb') as log:
        subprocess.run([FF,'-y','-hide_banner','-loglevel','error','-f','concat','-safe','0','-i',str(concat),'-c:v','copy','-af','volume=5dB','-c:a','aac','-b:a','144k','-ar','48000','-movflags','+faststart','-metadata','title=ChargeCheck - Korean narrated demo','-metadata:s:a:0','language=kor',str(final)],stdout=log,stderr=log,check=True)
    subtitle_path=OUT/'ChargeCheck_시연영상_자막.srt'
    subtitle_path.write_text('\n\n'.join(f'{i+1}\n{timecode(start)} --> {timecode(end)}\n{text}' for i,(start,end,text) in enumerate(subtitles))+'\n',encoding='utf-8-sig')
    (W/'timeline.json').write_text(json.dumps({'duration':cursor,'chapters':timeline,'subtitles':subtitles},ensure_ascii=False,indent=2),encoding='utf-8')
    # Decode the entire output to catch corrupt frames or truncated audio.
    with (W/'decode_check.log').open('wb') as log:
        subprocess.run([FF,'-v','error','-i',str(final),'-f','null','-'],stdout=log,stderr=log,check=True)
    subprocess.run([FF,'-v','error','-y','-ss','4','-i',str(final),'-frames:v','1',str(OUT/'ChargeCheck_시연영상_썸네일.jpg')],check=True)
    print('FINAL:',str(final),'duration:',round(cursor,2),'bytes:',final.stat().st_size,flush=True)
