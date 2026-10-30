"use strict";

/** Native, viewport-bounded drawing. Layout, picking and application state remain in RailixWorld. */
class RailixCanvas {
  static preview(ctx, members, palette, point) {
    const polygon = (points, fill) => {
      ctx.beginPath();points.forEach((p,i)=>i?ctx.lineTo(...point(...p)):ctx.moveTo(...point(...p)));
      ctx.closePath();ctx.fillStyle=fill;ctx.fill();
    };
    for (const member of members.slice(0,6)) {
      const x=18+64*member.x,y=18+64*member.y,w=5,h=8;
      const shape=member.branch
        ? [[x-w,y-h],[x+w,y-h],[x+w,y],[x+2,y],[x+2,y+h],[x-2,y+h],[x-2,y],[x-w,y]]
        : [[x-w,y-h],[x+w,y-h],[x+w,y+h],[x-w,y+h]];
      polygon(shape.map(([x,y])=>[x+2,y+3]),palette.wall);
      polygon(shape,palette.shade);
      polygon([[x-1,y-3],[x+1,y-3],[x+1,y+3],[x-1,y+3]],palette.rim);
    }
    return ctx;
  }

  static async load(url, signal) {
    if (!url) {
      const response = await fetch('/api/themes', {signal, cache:'no-store'});
      if (!response.ok) throw Error('Default Canvas assets are unavailable.');
      url = (await response.json()).defaultRenderer?.atlas;
      if (!url) throw Error('The embedded default theme requires a Canvas atlas.');
    }
    const root = new URL(url, location.href);
    if (root.origin !== location.origin || !['/api/themes/files/','/themes/'].some(prefix=>root.pathname.startsWith(prefix))) throw Error('Canvas atlas must be a local theme asset.');
    const response = await fetch(root, {signal, cache:'no-store'});
    if (!response.ok) throw Error(`Canvas atlas unavailable (HTTP ${response.status}).`);
    const atlas = await response.json();
    if (atlas.version !== 1 || !atlas.sprites || Array.isArray(atlas.sprites)) throw Error('Invalid Canvas atlas.');
    const entries = Object.entries(atlas.sprites), sprites = new Map();
    if (!entries.length || entries.length > 64) throw Error('Canvas atlas requires 1 to 64 reusable sprites.');
    let pixels = 0;
    try {
      for (const [id, spec] of entries) {
        if (!/^[a-z][a-z0-9-]*$/.test(id) || !spec || typeof spec.file !== 'string'
          || !/^[\w +/.-]+\.(png|webp|svg)$/.test(spec.file) || spec.file.split('/').some(part => !part || part.startsWith('.') || part.includes('..'))
          || !Number.isInteger(spec.frames) || spec.frames < 1 || spec.frames > 32
          || ![spec.scale,spec.anchorX,spec.anchorY,spec.period].every(Number.isFinite)
          || spec.scale <= 0 || spec.scale > 8 || spec.period < 100 || spec.period > 60000)
          throw Error(`Invalid Canvas sprite: ${id}.`);
        const asset = await fetch(new URL(spec.file, root), {signal, cache:'no-store'});
        if (!asset.ok) throw Error(`Canvas sprite ${id} unavailable (HTTP ${asset.status}).`);
        const image=new Image(),objectUrl=URL.createObjectURL(await asset.blob()),mask=document.createElement('canvas');
        let bitmap;
        try {
          image.src=objectUrl;
          await image.decode();
          signal?.throwIfAborted();
          pixels+=image.naturalWidth*image.naturalHeight;
          if(image.naturalWidth>8192||image.naturalHeight>4096||image.naturalWidth%spec.frames||pixels>16_777_216)
            throw Error('Canvas atlas exceeds its decoded image budget.');
          // SVG decoding into ImageBitmap differs between engines. Rasterize either source once.
          mask.width=image.naturalWidth;mask.height=image.naturalHeight;
          mask.getContext('2d').drawImage(image,0,0);
          bitmap=await createImageBitmap(mask);
        } finally {URL.revokeObjectURL(objectUrl);}
        mask.width = bitmap.width / spec.frames; mask.height = bitmap.height;
        const sprite = {...spec,bitmap,width:mask.width,height:mask.height};
        sprites.set(id,sprite);
        const context = mask.getContext('2d', {willReadFrequently:true});
        for (let frame=0;frame<spec.frames;frame++) context.drawImage(bitmap,frame*mask.width,0,mask.width,mask.height,0,0,mask.width,mask.height);
        const rgba = context.getImageData(0,0,mask.width,mask.height).data;
        const alpha = new Uint8Array(mask.width * mask.height);
        const bounds={x:mask.width,y:mask.height,right:0,bottom:0};
        for (let i=0;i<alpha.length;i++) {
          alpha[i]=rgba[i*4+3];
          if(alpha[i]>24) {
            const x=i%mask.width,y=Math.floor(i/mask.width);
            bounds.x=Math.min(bounds.x,x);bounds.y=Math.min(bounds.y,y);bounds.right=Math.max(bounds.right,x+1);bounds.bottom=Math.max(bounds.bottom,y+1);
          }
        }
        sprite.alpha=alpha;
        sprite.bounds=bounds;
        mask.width = mask.height = 0;
      }
      if (!sprites.has('step')) throw Error('Canvas atlas must define a step fallback sprite.');
      signal?.throwIfAborted();
      return {sprites, pixels, url:String(root)};
    } catch (error) { sprites.forEach(sprite => sprite.bitmap.close()); throw error; }
  }

  constructor(host, assets) {
    this.assets = assets;
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'world-raster';
    this.canvas.setAttribute('aria-hidden','true');
    this.context = this.canvas.getContext('2d');
    if (!this.context) throw Error('Canvas2D is unavailable in this browser.');
    this.ground = document.createElement('canvas');
    this.roofs = document.createElement('canvas');
    this.groundContext = this.ground.getContext('2d');
    this.roofContext = this.roofs.getContext('2d');
    host.append(this.canvas);
    this.time = 0;
    this.last = performance.now();
    this.runs=[];this.routes=[];this.stations=[];this.terminals=[];this.junctions=[];
    this.timeScale=[];
  }

  begin(size, projection, origin, theme) {
    cancelAnimationFrame(this.frame); this.frame = 0;
    this.paintedAt=0;
    this.size = size; this.m = projection; this.origin = origin;
    this.groupPreview = theme.getPropertyValue('--world-group-preview').trim() === '1';
    const requested = Number(theme.getPropertyValue('--canvas-resolution')) || 2;
    this.ratio = Math.min(Math.max(1, requested), window.devicePixelRatio || 1, Math.sqrt(8_388_608 / (size.width * size.height)));
    this.palette = {
      top:theme.getPropertyValue('--machine-light').trim() || '#eee9d9',
      shade:theme.getPropertyValue('--machine-shade').trim() || '#b8c7be',
      rim:theme.getPropertyValue('--belt-rim').trim() || '#7da7a5',
      bed:theme.getPropertyValue('--belt-bed').trim() || '#152c34',
      module:theme.getPropertyValue('--canvas-module').trim() || '#26343c',
      selected:theme.getPropertyValue('--canvas-selection').trim() || '#a9f5ec'
    };
    for(const [name,fallback] of Object.entries({wall:'#28414a',shadow:'#05121c88',error:'#ff7a64',warning:'#f1bb62',changed:'#d5c799',never:'#607b7e',example:'#8de4cf',exampleBed:'#355f61',timeShort:'#98e5c5',timeLong:'#93bbff',timeMissing:'#8ea7b4',tread:'#4e6d75',cargoTop:'#d4ffec',cargoLight:'#75b7a5',cargoShade:'#356c6c'}))
      this.palette[name]=theme.getPropertyValue('--canvas-'+name.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())).trim()||fallback;
    const timeKey=this.palette.timeShort+'|'+this.palette.timeLong;
    if(this.timeKey!==timeKey) {
      const ramp=document.createElement('canvas');ramp.width=64;ramp.height=1;
      const context=ramp.getContext('2d',{willReadFrequently:true}),gradient=context.createLinearGradient(0,0,63,0);
      gradient.addColorStop(0,this.palette.timeShort);gradient.addColorStop(1,this.palette.timeLong);
      context.fillStyle=gradient;context.fillRect(0,0,64,1);
      const pixels=context.getImageData(0,0,64,1).data;
      this.timeColors=Array.from({length:64},(_,i)=>`rgb(${pixels[i*4]},${pixels[i*4+1]},${pixels[i*4+2]})`);
      this.timeKey=timeKey;ramp.width=ramp.height=0;
    }
    for (const replay of [false,true]) {
      const tile=document.createElement('canvas');tile.width=32;tile.height=24;
      const plate=tile.getContext('2d');
      plate.fillStyle=this.palette.bed;plate.fillRect(0,0,32,24);
      plate.fillStyle=replay?this.palette.exampleBed:this.palette.tread;plate.fillRect(2,2,27,20);
      plate.fillStyle=this.palette.rim;plate.fillRect(3,2,24,2);
      plate.fillStyle=this.palette.bed;plate.fillRect(27,3,2,18);
      plate.strokeStyle=replay?this.palette.example:this.palette.rim;plate.globalAlpha=.75;plate.lineWidth=1.5;
      plate.beginPath();plate.moveTo(12,7);plate.lineTo(18,12);plate.lineTo(12,17);plate.stroke();
      if(replay)this.replayTread=this.context.createPattern(tile,'repeat');
      else this.tread=this.context.createPattern(tile,'repeat');
    }
    for (const canvas of [this.canvas,this.ground,this.roofs]) {
      const width = Math.max(1,Math.round(size.width*this.ratio)), height = Math.max(1,Math.round(size.height*this.ratio));
      if (canvas.width !== width || canvas.height !== height) { canvas.width=width;canvas.height=height; }
      else { const context=canvas.getContext('2d');context.resetTransform();context.clearRect(0,0,width,height); }
      canvas.getContext('2d').imageSmoothingQuality='high';
    }
    this.canvas.style.width=size.width+'px'; this.canvas.style.height=size.height+'px';
    this.runs=[]; this.routes=[]; this.stations=[]; this.terminals=[];this.junctions=[];this.bridges=[];
    this.groupPreviewCount=0;
    this.projected(this.groundContext);
    this.roofContext.setTransform(this.ratio,0,0,this.ratio,0,0);
    return this;
  }

  point(x,y,z=0) {
    const m=this.m;
    return [this.origin[0]+m.m11*x+m.m21*y+m.m31*z,this.origin[1]+m.m12*x+m.m22*y+m.m32*z];
  }

  projected(context,z=0) {
    const r=this.ratio,m=this.m;
    context.setTransform(r*m.m11,r*m.m12,r*m.m21,r*m.m22,r*(this.origin[0]+m.m31*z),r*(this.origin[1]+m.m32*z));
  }

  polygon(context,points,fill,stroke,width=1) {
    context.beginPath();points.forEach(([x,y],i)=>i?context.lineTo(x,y):context.moveTo(x,y));context.closePath();
    if (fill) { context.fillStyle=fill;context.fill(); }
    if (stroke) { context.strokeStyle=stroke;context.lineWidth=width;context.stroke(); }
  }

  timeColor(nanos) {
    if(!Number.isFinite(nanos)||nanos<0||!this.timeScale.length)return this.palette.timeMissing;
    const minimum=this.timeScale[0],maximum=this.timeScale.at(-1);
    const fraction=minimum===maximum ? .5 : Math.max(0,Math.min(1,
      (Math.log1p(nanos)-Math.log1p(minimum))/(Math.log1p(maximum)-Math.log1p(minimum))));
    return this.timeColors[Math.round(fraction*(this.timeColors.length-1))];
  }

  timing(values) {
    const measured=values.filter(value=>Number.isFinite(value)&&value>=0);
    const minimum=measured.length?Math.min(...measured):0, maximum=measured.length?Math.max(...measured):0;
    const scale=!measured.length?[]:minimum===maximum?[minimum]:[minimum,maximum];
    if(scale.length===this.timeScale.length&&scale.every((value,index)=>value===this.timeScale[index]))return false;
    this.timeScale=scale;
    return true;
  }

  run(run) {
    const {origin,length,unit,angle,look}=run,ctx=this.groundContext;
    this.runs.push(run);
    this.projected(ctx,6*unit);ctx.translate(...origin);ctx.rotate(angle);
    ctx.fillStyle=this.palette.shadow;ctx.fillRect(5*unit,4*unit,length,24*unit);
    ctx.fillStyle=look.power?'#16434c':look.selected&&!look.replay?this.palette.selected:this.timeColor(look.durationNanos);
    const width=(look.power?8:30)*unit;
    ctx.fillRect(0,-width/2,length,width);
    const rim=look.replay?Math.min(width/4,Math.max(1.5,2*unit)):2*unit;
    ctx.fillStyle=this.palette.bed;ctx.fillRect(0,-width/2+rim,length,width-2*rim);
  }

  junction(point,mask,look,unit,phase=0) {
    this.junctions.push({point,mask,look,unit,phase});
  }

  paintJunction(ctx,{point,mask,look,unit,phase:anchor=0},time) {
    const half=15*unit;
    this.projected(ctx,7*unit);
    ctx.fillStyle=this.palette.bed;ctx.fillRect(point[0]-half,point[1]-half,half*2,half*2);
    const period=Math.max(300,2400/(1+Math.log10(1+Math.max(0,look.rate||0))));
    const phase=((this.active(look)?time/period*32:0)+anchor)%32;
    ctx.save();ctx.beginPath();ctx.rect(point[0]-12*unit,point[1]-12*unit,24*unit,24*unit);ctx.clip();
    ctx.translate(point[0]+phase*unit,point[1]-12*unit);ctx.scale(unit,unit);
    ctx.fillStyle=look.replay?this.replayTread:this.tread;ctx.fillRect(-12-phase,0,24,24);ctx.restore();
    ctx.strokeStyle=look.selected&&!look.replay?this.palette.selected:this.timeColor(look.durationNanos);
    ctx.lineWidth=look.replay?Math.max(1.5,2*unit):2*unit;
    ctx.beginPath();
    for (const [bit,a,b] of [[1,[-1,-1],[1,-1]],[2,[1,-1],[1,1]],[4,[1,1],[-1,1]],[8,[-1,1],[-1,-1]]]) {
      if(mask&bit)continue;
      ctx.moveTo(point[0]+a[0]*half,point[1]+a[1]*half);ctx.lineTo(point[0]+b[0]*half,point[1]+b[1]*half);
    }
    ctx.stroke();
  }

  route(route) { this.routes.push(route); }

  bridge(bridge) { this.bridges.push(bridge); }

  paintBridge(ctx,bridge,time) {
    const {unit,look,forward}=bridge,points=forward?bridge.points:[...bridge.points].reverse();
    const rim=look.selected&&!look.replay?this.palette.selected:this.timeColor(look.durationNanos);
    const p=(x,y,z)=>this.point(x,y,z+6*unit);
    ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
    for(let index=1;index<points.length;index++) {
      const a=points[index-1],b=points[index];
      this.polygon(ctx,[p(a[0],a[1]-15*unit,a[2]),p(b[0],b[1]-15*unit,b[2]),
        p(b[0],b[1]+15*unit,b[2]),p(a[0],a[1]+15*unit,a[2])],this.palette.bed);
      this.polygon(ctx,[p(a[0],a[1]+15*unit,a[2]),p(b[0],b[1]+15*unit,b[2]),
        p(b[0],b[1]+15*unit,b[2]-5*unit),p(a[0],a[1]+15*unit,a[2]-5*unit)],this.palette.shadow);
      ctx.strokeStyle=rim;ctx.lineWidth=2*unit;ctx.beginPath();
      for(const side of [-15,15]){ctx.moveTo(...p(a[0],a[1]+side*unit,a[2]));ctx.lineTo(...p(b[0],b[1]+side*unit,b[2]));}
      ctx.stroke();
      const direction=forward?1:-1,length=Math.abs(b[0]-a[0]);
      const slope=(b[2]-a[2])/(b[0]-a[0]),m=this.m,r=this.ratio,origin=p(a[0],a[1],a[2]);
      ctx.setTransform(r*direction*(m.m11+m.m31*slope),r*direction*(m.m12+m.m32*slope),
        r*direction*m.m21,r*direction*m.m22,r*origin[0],r*origin[1]);
      const period=Math.max(300,2400/(1+Math.log10(1+Math.max(0,look.rate||0))));
      const phase=((this.active(look)?time/period*32:0)+(bridge.phase-(a[0]-bridge.at[0])/unit)*direction)%32;
      ctx.save();ctx.beginPath();ctx.rect(0,-12*unit,length,24*unit);ctx.clip();
      ctx.translate(phase*unit,-12*unit);ctx.scale(unit,unit);ctx.fillStyle=look.replay?this.replayTread:this.tread;ctx.fillRect(-phase,0,length/unit,24);ctx.restore();
      ctx.setTransform(r,0,0,r,0,0);
    }
  }

  station(station,contour,unit,reveal) {
    const {node,box,look}=station,ctx=this.roofContext;
    const signal=look.error?this.palette.error:look.warning?this.palette.warning:look.changed?this.palette.changed:look.never?this.palette.never:look.color||this.palette.rim;
    const top=contour.map(p=>this.point(...p,node.expanded||node.kind==='end'?0:18*unit));
    if (node.expanded) {
      this.groundContext.setTransform(this.ratio,0,0,this.ratio,0,0);
      this.groundContext.globalAlpha=.035;
      this.polygon(this.groundContext,top,signal);
      this.groundContext.globalAlpha=1;
      this.groundContext.globalAlpha=.3;
      this.polygon(this.groundContext,top,null,this.palette.rim,1);
      this.groundContext.globalAlpha=1;return;
    }
    if (node.kind==='end') {
      this.projected(ctx);
      const cx=box.x+box.width/2,cy=box.y+box.height/2;
      for (const [radius,fill] of [[.5,this.palette.wall],[.43,look.coverage==='selected'?this.palette.example:this.palette.rim],
        [.32,this.palette.bed],[.24,'#020e16']]) {
        ctx.beginPath();ctx.ellipse(cx,cy,box.width*radius,box.height*radius,0,0,Math.PI*2);ctx.fillStyle=fill;ctx.fill();
      }
      ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
      this.terminals.push({box,look,unit,signal});return;
    }
    this.polygon(ctx,contour.map(p=>this.point(p[0]+4*unit,p[1]+8*unit)),this.palette.shadow);
    const base=contour.map(p=>this.point(...p));
    for (let i=0;i<contour.length;i++) {
      const j=(i+1)%contour.length;
      if(this.m.m13*(contour[j][1]-contour[i][1])-this.m.m23*(contour[j][0]-contour[i][0])<=0)continue;
      this.polygon(ctx,[top[i],top[j],base[j],base[i]],this.palette.wall);
    }
    this.polygon(ctx,top,this.palette.module,look.error||look.warning?signal:look.selected?this.palette.selected
      :look.coverage==='selected'||node.kind==='app'&&look.activity==='active'?this.palette.example:this.palette.rim,Math.max(.75,unit));
    const sockets=new Set();
    for (const {point,other} of station.contacts || []) {
      const key=point.map(value=>Math.round(value*10)).join(':');
      if(sockets.has(key)) continue;
      sockets.add(key);
      const angle=Math.atan2(other[1]-point[1],other[0]-point[0]);
      this.projected(ctx,6*unit);ctx.translate(...point);ctx.rotate(angle);
      ctx.fillStyle=this.palette.wall;ctx.fillRect(-6*unit,-15*unit,12*unit,30*unit);
      ctx.fillStyle=this.palette.bed;ctx.fillRect(-5*unit,-11*unit,10*unit,22*unit);
      ctx.fillStyle=this.palette.rim;
      ctx.fillRect(-6*unit,-15*unit,12*unit,2*unit);ctx.fillRect(-6*unit,13*unit,12*unit,2*unit);
    }
    this.projected(ctx,18*unit);
    if(look.error || look.warning) {ctx.fillStyle=signal;ctx.fillRect(box.x+1*unit,box.y+10*unit,2*unit,Math.max(0,box.height-20*unit));}
    if (node.summary === 'run') {
      const p=(x,y)=>this.point(box.x+box.width*x,box.y+box.height*y,18*unit);
      for (const y of [.22,.78]) this.polygon(ctx,[p(.07,y),p(.93,y),p(.93,y+.035),p(.07,y+.035)],signal);
      for (const x of [.19,.43,.67]) this.polygon(ctx,
        [p(x,.35),p(x+.12,.35),p(x+.12,.67),p(x,.67)],this.palette.rim);
      return;
    }
    const role=node.kind==='step'&&look.symbol==='branch'?'branch':node.kind;
    const sprite=this.assets.sprites.get(look.symbol)||this.assets.sprites.get(role)||this.assets.sprites.get('step');
    const center=this.point(box.x+28*unit,box.y+box.height*.5,18*unit);
    const size=Math.min(node.kind==='region'?42:26,box.width/unit*.22,box.height/unit*.6)*unit;
    const bounds=sprite.bounds || {x:0,y:0,right:sprite.width,bottom:sprite.height};
    const factor=size/Math.max(1,bounds.right-bounds.x,bounds.bottom-bounds.y);
    const image={sprite,x:center[0]-(bounds.x+bounds.right)/2*factor,y:center[1]-(bounds.y+bounds.bottom)/2*factor,
      width:sprite.width*factor,height:sprite.height*factor,node,look,unit,signal,factor,reveal,box,
      groupUnit:size/95.2,
      group:this.groupPreview && node.kind==='region' && node.roof?.length ? node.roof : null};
    this.stations.push(image);
    if(image.group) this.groupPreviewCount++;
    image.foundationHit=new Path2D();
    for(const face of [top,...top.map((point,i)=>[point,top[(i+1)%top.length],base[(i+1)%top.length],base[i]])]) {
      face.forEach(([x,y],i)=>i?image.foundationHit.lineTo(x,y):image.foundationHit.moveTo(x,y));image.foundationHit.closePath();
    }
    if((image.group||sprite.frames===1)&&!reveal)this.image(ctx,image,0);
  }

  image(ctx,item,time) {
    const {sprite,look}=item;
    const phase=time%sprite.period/sprite.period;
    const frame=this.active(look)?Math.round((1-Math.abs(phase*2-1))*(sprite.frames-1)):0;
    let dx=0,dy=0;
    if(item.reveal){const amount=Math.max(0,1-(performance.now()-item.reveal.start)/180);dx=item.reveal.dx*amount;dy=item.reveal.dy*amount;}
    ctx.globalAlpha=1;
    if(item.group) {
      const origin=this.point(item.box.x+28*item.unit,item.box.y+item.box.height*.5,3*item.unit);
      item.groupGeometry=this.group(ctx,item.group,[origin[0]+dx,origin[1]+dy],item.groupUnit);
      return;
    }
    ctx.drawImage(sprite.bitmap,frame*sprite.width,0,sprite.width,sprite.height,item.x+dx,item.y+dy,item.width,item.height);
    ctx.globalAlpha=1;
  }

  active(look) { return this.motion?.enabled && (look.rate>0&&this.motion.moving || look.replay || look.coverage==='selected'); }

  group(ctx, members, origin, unit) {
    const m=this.m,p=(x,y,z=0)=>[origin[0]+unit*(m.m11*x+m.m21*y+m.m31*z),
      origin[1]+unit*(m.m12*x+m.m22*y+m.m32*z)];
    const roof=[p(-35.7,-35.7,65.6),p(35.7,-35.7,65.6),p(35.7,35.7,65.6),p(-35.7,35.7,65.6)];
    const base=[p(-47.6,-47.6,18),p(47.6,-47.6,18),p(47.6,47.6,18),p(-47.6,47.6,18)];
    const front=[roof[2],roof[3],base[3],base[2]],side=[roof[3],roof[0],base[0],base[3]];
    RailixCanvas.preview(ctx,members,this.palette,(x,y)=>p((x-50)*.838,(y-50)*.628+2.856,20));
    ctx.globalAlpha=.16;
    this.polygon(ctx,front,this.palette.rim);
    this.polygon(ctx,side,this.palette.rim);
    this.polygon(ctx,roof,this.palette.rim);
    ctx.globalAlpha=.55;
    for(const face of [roof,front,side])this.polygon(ctx,face,null,this.palette.rim,Math.max(.5,unit));
    ctx.globalAlpha=.08;
    this.polygon(ctx,[roof[0],roof[1],roof[2]],this.palette.top);
    ctx.globalAlpha=1;
    const hit = new Path2D();
    for(const face of [roof,front,side]) {
      face.forEach(([x,y],i)=>i?hit.lineTo(x,y):hit.moveTo(x,y));hit.closePath();
    }
    return {hit,light:p(0,0,65.6),badge:[roof[0][0],roof[0][1]-4]};
  }

  portrait(node,look,host) {
    const role=node.kind==='step'&&look.symbol==='branch'?'branch':node.kind,symbol=node.summary==='run'?'step':look.symbol;
    const key=JSON.stringify([role,symbol,node.roof]);
    if(host.railixAssets===this.assets && host.railixPortrait===key) return;
    host.railixAssets=this.assets;host.railixPortrait=key;
    const sprite=this.assets.sprites.get(symbol)||this.assets.sprites.get(role)||this.assets.sprites.get('step'),b=sprite.bounds;
    const canvas=document.createElement('canvas'),ratio=Math.min(2,devicePixelRatio||1);
    canvas.width=canvas.height=112*ratio;canvas.style.width=canvas.style.height='112px';
    canvas.setAttribute('aria-hidden','true');
    const width=b.right-b.x,height=b.bottom-b.y,factor=92/Math.max(1,width,height);
    const ctx=canvas.getContext('2d');ctx.imageSmoothingQuality='high';
    if(this.groupPreview && node.kind==='region' && node.roof?.length) {
      ctx.scale(ratio,ratio);this.group(ctx,node.roof,[56,88],.62);
    } else if(width>0&&height>0)ctx.drawImage(sprite.bitmap,b.x,b.y,width,height,
      (112-width*factor)/2*ratio,(112-height*factor)/2*ratio,width*factor*ratio,height*factor*ratio);
    host.replaceChildren(canvas);
    return this;
  }

  animate(motion) {
    this.motion=motion;
    if(!this.size||this.disposed)return this;
    if(!this.frame)this.draw(performance.now());
    return this;
  }

  draw(now) {
    this.frame=0;
    if(this.disposed)return;
    if(this.motion.enabled)this.time+=Math.min(100,now-this.last);
    this.last=now;
    const activeTraffic=this.runs.some(run=>this.active(run.look))
      ||this.stations.some(item=>this.active(item.look))||this.terminals.some(item=>this.active(item.look));
    const powered=this.motion.powered&&(this.stations.some(item=>!item.look.never)||this.runs.some(run=>run.look.power));
    const moving=this.motion.enabled&&(activeTraffic||powered);
    if(moving&&!activeTraffic&&this.paintedAt&&now-this.paintedAt<50){
      this.frame=requestAnimationFrame(next=>this.draw(next));return;
    }
    this.paintedAt=now;
    const ctx=this.context,r=this.ratio,time=this.time;
    ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,this.canvas.width,this.canvas.height);ctx.drawImage(this.ground,0,0);
    for(const run of this.runs){
      const {origin,length,unit,angle,look,phase}=run;
      this.projected(ctx,6*unit);ctx.translate(...origin);ctx.rotate(angle);
      const moving=look.power?this.motion.enabled&&this.motion.powered:this.active(look);
      const period=2400/(1+Math.log10(1+Math.max(0,look.rate||0)));
      const cycle=look.power?160:32;
      const offset=(((moving?time/Math.max(300,period)*32:0)+phase)%cycle+cycle)%cycle;
      ctx.save();ctx.beginPath();ctx.rect(0,(look.power?-2:-12)*unit,length,(look.power?4:24)*unit);ctx.clip();
      if(look.power) {
        ctx.strokeStyle=this.palette.selected;ctx.lineWidth=unit;ctx.beginPath();
        for(let x=((offset*unit)%(160*unit)+160*unit)%(160*unit)-160*unit;x<length;x+=160*unit){
          ctx.moveTo(x,0);ctx.lineTo(x+50*unit,0);
        }
        ctx.stroke();
      } else {
        ctx.translate(offset*unit,-12*unit);ctx.scale(unit,unit);
        ctx.fillStyle=look.replay?this.replayTread:this.tread;ctx.fillRect(-offset,0,length/unit,24);
      }
      ctx.restore();
    }
    for(const junction of this.junctions)this.paintJunction(ctx,junction,time);
    const raised=[];
    for(const route of this.routes){
      if(!this.active(route.look))continue;
      const speed=32/Math.max(.3,2.4/(1+Math.log10(1+Math.max(0,route.look.rate||0))));
      const offset=(time/1000*speed)%64;
      for(const segment of route.segments){
        const start=Math.ceil((segment.from-offset)/64)*64+offset;
        for(let d=start;d<segment.to;d+=64){
          const t=(d-segment.offset)/segment.length,a=segment.a,b=segment.b;
          const x=a[0]+(b[0]-a[0])*t,y=a[1]+(b[1]-a[1])*t,lift=(a[2]||0)+((b[2]||0)-(a[2]||0))*t;
          if(lift>0)raised.push([x,y,route.unit,lift/route.unit]);
          else this.parcel(ctx,x,y,route.unit);
        }
      }
    }
    for(const bridge of this.bridges)this.paintBridge(ctx,bridge,time);
    for(const parcel of raised)this.parcel(ctx,...parcel);
    for(const item of this.stations)if(item.reveal && now-item.reveal.start>=180) {
      item.reveal=null;
      if(item.group||item.sprite.frames===1)this.image(this.roofContext,item,time);
    }
    ctx.setTransform(1,0,0,1,0,0);ctx.drawImage(this.roofs,0,0);ctx.setTransform(r,0,0,r,0,0);
    for(const item of this.stations){
      if(!item.group&&item.sprite.frames>1||item.reveal)this.image(ctx,item,time);
      if(!item.look.never && (this.motion.powered||this.active(item.look))){
        const height=item.unit*3;
        ctx.globalAlpha=this.motion.enabled ? .25+.45*(.5+.5*Math.sin(time/(this.active(item.look)?500:1800))) : .3;
        ctx.fillStyle=item.signal;
        const light=item.groupGeometry?.light || [item.x+item.width*.5,item.y+item.height*.38];
        ctx.beginPath();ctx.arc(...light,Math.max(1.2,height),0,Math.PI*2);ctx.fill();ctx.globalAlpha=1;
      }
      this.paintEvidence(ctx,item);
      this.paintSelection(ctx,item);
    }
    for(const terminal of this.terminals)if(this.active(terminal.look)){
      this.projected(ctx);
      const phase=time%1800/1800,box=terminal.box;
      ctx.beginPath();ctx.ellipse(box.x+box.width/2,box.y+box.height/2,box.width*.19*(1-phase),box.height*.19*(1-phase),0,0,Math.PI*2);
      ctx.fillStyle=terminal.signal;ctx.globalAlpha=1-phase;ctx.fill();ctx.globalAlpha=1;
    }
    ctx.setTransform(1,0,0,1,0,0);
    const values={animated:String(moving),stations:String(this.stations.length),routes:String(this.routes.length),
      bridges:String(this.bridges.length),groupPreviews:String(this.groupPreviewCount)};
    for(const key in values)if(this.canvas.dataset[key]!==values[key])this.canvas.dataset[key]=values[key];
    if(moving||this.stations.some(item=>item.reveal&&now-item.reveal.start<180))this.frame=requestAnimationFrame(next=>this.draw(next));
  }

  parcel(ctx,x,y,unit,lift=0) {
    ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
    const p=(dx,dy,z)=>this.point(x+dx*unit,y+dy*unit,(z+lift)*unit);
    this.polygon(ctx,[p(-6,-6,18),p(6,-6,18),p(6,6,18),p(-6,6,18)],this.palette.cargoTop);
    this.polygon(ctx,[p(-6,6,18),p(6,6,18),p(6,6,6),p(-6,6,6)],this.palette.cargoLight);
    this.polygon(ctx,[p(-6,-6,18),p(-6,6,18),p(-6,6,6),p(-6,-6,6)],this.palette.cargoShade);
  }

  paintEvidence(ctx,item) {
    const {look,sprite}=item;
    if(!look.error && !look.warning && look.coverage!=='uncovered')return;
    const [x,y]=item.groupGeometry?.badge || [item.x+(sprite.bounds.x+sprite.bounds.right)*item.factor/2,item.y+sprite.bounds.y*item.factor-4];
    const size=!look.error && look.coverage==='uncovered'?Math.max(2.5,Math.min(5,item.unit*5)):6;
    ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
    ctx.fillStyle=look.error?this.palette.error:look.warning?this.palette.warning:'#40545b';
    ctx.strokeStyle='#102832';ctx.lineWidth=2;
    ctx.beginPath();ctx.arc(x,y,size+2,0,Math.PI*2);ctx.fill();ctx.stroke();
    ctx.strokeStyle=look.error||look.warning?'#e5f4f1':this.palette.rim;ctx.lineWidth=1.7;ctx.lineCap='round';ctx.beginPath();
    if(look.error||look.warning){ctx.moveTo(x,y-4);ctx.lineTo(x,y);ctx.moveTo(x,y+3);ctx.lineTo(x,y+3.2);}
    else {ctx.moveTo(x-3,y+3);ctx.lineTo(x+3,y-3);}
    ctx.stroke();ctx.lineCap='butt';
  }

  paintSelection(ctx,item) {
    const {look,box,unit}=item;
    if(!look.selected && !look.related && !look.editing)return;
    if(look.selected && !look.editing)return;
    ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
    ctx.strokeStyle=look.error||look.warning?item.signal:this.palette.selected;
    ctx.lineWidth=1.5;
    ctx.setLineDash(look.editing || look.related && !look.selected?[3,3]:[]);
    const points=[[box.x-10*unit,box.y-10*unit],[box.x+box.width+10*unit,box.y-10*unit],
      [box.x+box.width+10*unit,box.y+box.height+10*unit],[box.x-10*unit,box.y+box.height+10*unit]].map(p=>this.point(...p));
    this.polygon(ctx,points,null,ctx.strokeStyle,1.5);
    ctx.setLineDash([]);
  }

  hit(x,y,kind) {
    for(let i=this.stations.length-1;i>=0;i--){
      const item=this.stations[i],sprite=item.sprite;
      if(kind&&item.node.kind!==kind)continue;
      if(this.context.isPointInPath(item.foundationHit,x,y))return item.node;
      if(item.group) {
        if(this.context.isPointInPath(item.foundationHit,x,y)
          ||item.groupGeometry&&this.context.isPointInPath(item.groupGeometry.hit,x,y))return item.node;
        continue;
      }
      const px=Math.floor((x-item.x)/item.width*sprite.width),py=Math.floor((y-item.y)/item.height*sprite.height);
      if(px>=0&&py>=0&&px<sprite.width&&py<sprite.height&&sprite.alpha[py*sprite.width+px]>24)return item.node;
    }
    return null;
  }

  dispose() {
    this.disposed=true;cancelAnimationFrame(this.frame);
    this.tread=this.replayTread=this.timeColors=null;
    this.assets.sprites.forEach(sprite=>sprite.bitmap.close());this.assets.sprites.clear();
    for(const canvas of [this.canvas,this.ground,this.roofs])canvas.width=canvas.height=0;
    this.canvas.remove();this.runs=this.routes=this.stations=this.terminals=this.junctions=[];
    return this;
  }
}
