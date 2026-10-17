/** Optional Worker/Wasm media adapter. All protocol assembly remains in common Core. */
const LIMIT=32*1024*1024, OUTPUT=48*1024*1024;
const base=new URL('./',import.meta.url);

function sequence(original, retained) {
  if (!retained.length) return [];
  // Require one unambiguous contiguous packet sequence, not a guessed timestamp.
  const starts=[];
  for(let i=0;i+retained.length<=original.length;i++) {
    if(original[i].data_hash!==retained[0].data_hash) continue;
    if(retained.every((packet,j)=>packet.data_hash&&packet.data_hash===original[i+j].data_hash)) starts.push(i);
  }
  if(starts.length!==1) throw Error('无法唯一验证保留的编码数据包；不发布结果');
  return original.slice(starts[0],starts[0]+retained.length);
}

export function startVideoEdit(file,{start=0,end,transcodeVideo=false}={}) {
  let engine=null, cancelled=false, timer;
  let rejectCancel;
  const abort=new Promise((_,reject)=>{rejectCancel=reject;});
  const timeout=new Promise((_,reject)=>{timer=setTimeout(()=>{engine?.terminate();reject(Error('媒体处理超时（120 秒）'));},120000);});
  const operation=(async()=>{
    if(!(file instanceof Blob)||file.size>LIMIT) throw Error('视频必须为不超过 32 MiB 的 File/Blob');
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<=start||typeof transcodeVideo!=='boolean') throw Error('无效的 A/B 范围或转码参数');
    const {FFmpeg}=await import('./vendor/ffmpeg/index.js');
    if(cancelled) throw Error('已取消');
    engine=new FFmpeg();
    const logs=[];
    engine.on('log',({message})=>{logs.push(String(message).slice(0,500));if(logs.length>8)logs.shift();});
    const work=(async()=>{
      await engine.load({coreURL:new URL('vendor/core/ffmpeg-core.js',base).href,
        wasmURL:new URL('vendor/core/ffmpeg-core.wasm',base).href});
      await engine.writeFile('input.mp4',new Uint8Array(await file.arrayBuffer()));
      let probeNumber=0;
      const probe=async(name)=>{
        const reportName=`probe-${probeNumber++}.json`;
        const args=['-v','error','-show_entries',
          'format=duration:stream=index,codec_type,codec_name,pix_fmt,color_transfer,color_primaries:stream_side_data=side_data_type:packet=stream_index,pts_time,dts_time,duration_time,flags,data_hash',
          '-show_error','-show_streams','-show_format','-show_packets','-show_data_hash','sha256','-of','json',name,'-o',reportName];
        const status=await engine.ffprobe(args,30000);
        // Upstream #817: 0.12.10's C entry returns normally, while its JS binding
        // reads an unset `ret` (-1). Never treat -1 alone as success: require a
        // fresh, complete, error-free JSON report and then verify packet payloads.
        if(status!==0&&status!==-1) throw Error('FFprobe 无法读取媒体：'+logs.join('\n').slice(0,2000));
        const text=await engine.readFile(reportName,'utf8');
        if(text.length>16*1024*1024) throw Error('采样表超过编辑预算');
        const report=JSON.parse(text);
        if(report.error||!report.format||!Array.isArray(report.streams)||!report.streams.length||
          !Array.isArray(report.packets)||!report.packets.length) throw Error('FFprobe 报告不完整或媒体损坏：'+logs.join('\n').slice(0,2000));
        return report;
      };
      const before=await probe('input.mp4');
      const duration=Number(before.format?.duration);
      if(!Number.isFinite(duration)||duration>60||end>duration+0.001) throw Error('编辑限 60 秒以内，B 不能超出视频时长');
      const streams=before.streams??[];
      if(streams.filter(s=>s.codec_type==='video').length!==1||streams.filter(s=>s.codec_type==='audio').length>1||
        streams.some(s=>!['video','audio'].includes(s.codec_type))) throw Error('编辑目前只接受一条视频和至多一条音轨，不丢弃其它轨道');
      const video=streams.find(s=>s.codec_type==='video');
      if(transcodeVideo && (!['yuv420p','yuvj420p','nv12'].includes(video.pix_fmt)||
        ['smpte2084','arib-std-b67'].includes(video.color_transfer)||video.color_primaries==='bt2020'||
        video.side_data_list?.some(s=>/mastering|content light|dovi|hdr/i.test(s.side_data_type)))) {
        throw Error('转码目前仅接受 8-bit SDR；不自动降低 HDR、位深或宽色域');
      }
      const args=['-hide_banner','-loglevel','error','-ss',String(start),'-i','input.mp4','-t',String(end-start),'-map','0:v:0','-map','0:a?',
        '-c:v',...(transcodeVideo?['libx264','-preset','ultrafast','-crf','23','-pix_fmt','yuv420p']:['copy']),
        '-c:a','copy','-map_metadata','0','-avoid_negative_ts','make_zero','-video_track_timescale','1000000','-movflags','+faststart','output.mp4'];
      if(await engine.exec(args,90000)!==0) throw Error('FFmpeg 处理失败；不会自动改用其它编码或重新编码音频。'+logs.join('\n').slice(0,2000));
      const after=await probe('output.mp4');
      if(after.streams.length!==streams.length) throw Error('输出轨道数量不匹配');
      let origin=null, actualStart=null, actualEnd=null;
      for(const original of streams) {
        const output=after.streams.filter(s=>s.codec_type===original.codec_type);
        if(output.length!==1) throw Error('输出轨道不明确');
        const coded=transcodeVideo&&original.codec_type==='video';
        if(!coded&&output[0].codec_name!==original.codec_name) throw Error('copy 操作改变了编码类型');
        const retained=(after.packets??[]).filter(p=>p.stream_index===output[0].index);
        if(!retained.length) throw Error('裁剪后没有可用的 '+original.codec_type+' 数据包');
        if(!coded) {
          const matched=sequence((before.packets??[]).filter(p=>p.stream_index===original.index),retained);
          if(original.codec_type==='video') {
            if(!retained[0].flags?.includes('K')) throw Error('copy 输出未从可解码关键帧开始');
            origin=Number(matched[0].pts_time)-Number(retained[0].pts_time);
            actualStart=Math.min(...matched.map(p=>Number(p.pts_time)));
            actualEnd=Math.max(...matched.map(p=>Number(p.pts_time)+Number(p.duration_time??0)));
            if(![origin,actualStart,actualEnd].every(Number.isFinite)||actualStart<0) throw Error('无法验证实际裁剪时间，或存在隐藏前导画面');
          }
        }
      }
      if(transcodeVideo && after.streams.find(s=>s.codec_type==='video')?.codec_name!=='h264') throw Error('输出不是 H.264');
      const data=await engine.readFile('output.mp4');
      if(!data.length||data.length>OUTPUT) throw Error('输出超过 48 MiB 或为空');
      return {blob:new Blob([data],{type:'video/mp4'}),requestedStart:start,requestedEnd:end,
        actualStart,actualEnd,origin,transcoded:transcodeVideo,audioCopied:true,
        // Re-encoded frames cannot be matched by payload digest. Do not pretend
        // requested boundaries are measured, or reuse a stale C metadata time.
        timelineKnown:!transcodeVideo};
    })();
    return await work;
  })();
  const promise=Promise.race([operation,abort,timeout])
    .catch(error=>{throw error instanceof Error?error:Error(String(error));})
    .finally(()=>{clearTimeout(timer);engine?.terminate();});
  return {promise,cancel(){cancelled=true;engine?.terminate();rejectCancel(Error('已取消'));}};
}
