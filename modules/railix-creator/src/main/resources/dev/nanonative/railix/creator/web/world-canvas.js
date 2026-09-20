"use strict";

/** Native, viewport-bounded drawing. Layout, picking and application state remain in RailixWorld. */
class RailixCanvas {
  static async load(url, signal) {
    const root = new URL(url, location.href);
    if (root.origin !== location.origin || !root.pathname.startsWith('/api/themes/files/')) throw Error('Canvas atlas must be a local theme asset.');
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
          || !/^[\w +/.-]+\.(png|svg)$/.test(spec.file) || spec.file.split('/').some(part => !part || part.startsWith('.') || part.includes('..'))
          || !Number.isInteger(spec.frames) || spec.frames < 1 || spec.frames > 32
          || ![spec.scale,spec.anchorX,spec.anchorY,spec.period].every(Number.isFinite)
          || spec.scale <= 0 || spec.scale > 8 || spec.period < 100 || spec.period > 60000) throw Error(`Invalid Canvas sprite: ${id}.`);
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
          if(alpha[i]>24) {const x=i%mask.width,y=Math.floor(i/mask.width);bounds.x=Math.min(bounds.x,x);bounds.y=Math.min(bounds.y,y);bounds.right=Math.max(bounds.right,x+1);bounds.bottom=Math.max(bounds.bottom,y+1);}
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
  }

  begin(size, projection, origin, theme) {
    cancelAnimationFrame(this.frame); this.frame = 0;
    this.size = size; this.m = projection; this.origin = origin;
    const requested = Number(theme.getPropertyValue('--canvas-resolution')) || 1;
    this.ratio = Math.min(Math.max(1, requested), window.devicePixelRatio || 1, Math.sqrt(8_388_608 / (size.width * size.height)));
    this.palette = {
      top:theme.getPropertyValue('--machine-light').trim() || '#eee9d9',
      shade:theme.getPropertyValue('--machine-shade').trim() || '#b8c7be',
      rim:theme.getPropertyValue('--belt-rim').trim() || '#7da7a5',
      bed:theme.getPropertyValue('--belt-bed').trim() || '#152c34',
      selected:theme.getPropertyValue('--canvas-selection').trim() || '#a9f5ec'
    };
    for(const [name,fallback] of Object.entries({wall:'#28414a',shadow:'#05121c88',error:'#ff7a64',warning:'#f1bb62',changed:'#d5c799',never:'#607b7e',tread:'#4e6d75',cargoTop:'#d4ffec',cargoLight:'#75b7a5',cargoShade:'#356c6c'}))
      this.palette[name]=theme.getPropertyValue('--canvas-'+name.replace(/[A-Z]/g,letter=>'-'+letter.toLowerCase())).trim()||fallback;
    for (const canvas of [this.canvas,this.ground,this.roofs]) {
      const width = Math.max(1,Math.round(size.width*this.ratio)), height = Math.max(1,Math.round(size.height*this.ratio));
      if (canvas.width !== width || canvas.height !== height) { canvas.width=width;canvas.height=height; }
      else { const context=canvas.getContext('2d');context.resetTransform();context.clearRect(0,0,width,height); }
    }
    this.canvas.style.width=size.width+'px'; this.canvas.style.height=size.height+'px';
    this.runs=[]; this.routes=[]; this.stations=[]; this.terminals=[];this.junctions=[];
    this.sceneryCount=0;
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

  run(run) {
    const {origin,length,unit,angle,look}=run,ctx=this.groundContext;
    this.runs.push(run);
    this.projected(ctx,6*unit);ctx.translate(...origin);ctx.rotate(angle);
    ctx.fillStyle=this.palette.shadow;ctx.fillRect(5*unit,4*unit,length,24*unit);
    ctx.fillStyle=look.power?'#16434c':look.selected?this.palette.selected:this.palette.rim;
    const width=(look.power?8:30)*unit;
    ctx.fillRect(0,-width/2,length,width);
    ctx.fillStyle=this.palette.bed;ctx.fillRect(0,-width/2+2*unit,length,width-4*unit);
  }

  junction(point,mask,look,unit) {
    this.junctions.push({point,mask,look,unit});
  }

  paintJunction(ctx,{point,mask,look,unit}) {
    const half=15*unit;
    this.projected(ctx,7*unit);
    ctx.fillStyle=this.palette.bed;ctx.fillRect(point[0]-half,point[1]-half,half*2,half*2);
    ctx.strokeStyle=look.selected?this.palette.selected:this.palette.rim;ctx.lineWidth=2*unit;
    ctx.beginPath();
    for (const [bit,a,b] of [[1,[-1,-1],[1,-1]],[2,[1,-1],[1,1]],[4,[1,1],[-1,1]],[8,[-1,1],[-1,-1]]]) {
      if(mask&bit)continue;
      ctx.moveTo(point[0]+a[0]*half,point[1]+a[1]*half);ctx.lineTo(point[0]+b[0]*half,point[1]+b[1]*half);
    }
    ctx.stroke();
  }

  route(route) { this.routes.push(route); }

  scenery(variant,box,unit) {
    const sprite=this.assets.sprites.get('scenery-'+variant);
    if(!sprite)return false;
    const [x,y]=this.point(box.x,box.y,-80),factor=unit/sprite.scale;
    this.groundContext.setTransform(this.ratio,0,0,this.ratio,0,0);
    this.groundContext.drawImage(sprite.bitmap,0,0,sprite.width,sprite.height,
      x-sprite.anchorX*factor,y-sprite.anchorY*factor,sprite.width*factor,sprite.height*factor);
    this.sceneryCount++;
    return true;
  }

  station(station,contour,unit,reveal) {
    const {node,box,look}=station,ctx=this.roofContext;
    const signal=look.error?this.palette.error:look.warning?this.palette.warning:look.changed?this.palette.changed:look.never?this.palette.never:look.color||'#53d8d1';
    const top=contour.map(p=>this.point(...p,node.expanded||node.kind==='end'?0:18*unit));
    if (node.expanded) {
      this.groundContext.setTransform(this.ratio,0,0,this.ratio,0,0);
      this.groundContext.globalAlpha=.035;
      this.polygon(this.groundContext,top,signal);
      this.groundContext.globalAlpha=1;
      this.polygon(this.groundContext,top,null,signal,1.5);return;
    }
    if (node.kind==='end') {
      this.projected(ctx);
      const cx=box.x+box.width/2,cy=box.y+box.height/2;
      for (const [radius,fill] of [[.5,'#253c45'],[.43,this.palette.shade],[.32,'#18343c'],[.24,'#020e16']]) {
        ctx.beginPath();ctx.ellipse(cx,cy,box.width*radius,box.height*radius,0,0,Math.PI*2);ctx.fillStyle=fill;ctx.fill();
      }
      ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
      this.terminals.push({box,look,unit,signal});return;
    }
    this.polygon(ctx,contour.map(p=>this.point(p[0]+12*unit,p[1]+20*unit)),this.palette.shadow);
    const base=contour.map(p=>this.point(...p));
    for (let i=0;i<contour.length;i++) {
      const j=(i+1)%contour.length;
      if(this.m.m13*(contour[j][1]-contour[i][1])-this.m.m23*(contour[j][0]-contour[i][0])<=0)continue;
      this.polygon(ctx,[top[i],top[j],base[j],base[i]],this.palette.wall);
    }
    const material=look.never||look.error||look.warning||look.changed?signal:this.palette.top;
    this.polygon(ctx,top,material,this.palette.shade,Math.max(.5,unit));
    if (look.selected) {
      const expanded={x:box.x-10*unit,y:box.y-10*unit,width:box.width+20*unit,height:box.height+20*unit};
      const points=[[expanded.x,expanded.y],[expanded.x+expanded.width,expanded.y],[expanded.x+expanded.width,expanded.y+expanded.height],[expanded.x,expanded.y+expanded.height]].map(p=>this.point(...p));
      this.polygon(ctx,points,null,look.error||look.warning?signal:this.palette.selected,Math.max(1.5,2*unit));
    }
    const role=node.kind==='step'&&look.symbol==='branch'?'branch':node.kind;
    const sprite=this.assets.sprites.get(look.symbol)||this.assets.sprites.get(role)||this.assets.sprites.get('step');
    const factor=Math.min(100,box.width/unit*.7,box.height/unit*.7)/95.2*unit;
    const center=this.point(box.x+box.width/2,box.y+box.height/2);
    const image={sprite,x:center[0]-sprite.anchorX/sprite.scale*factor,y:center[1]-sprite.anchorY/sprite.scale*factor,
      width:sprite.width/sprite.scale*factor,height:sprite.height/sprite.scale*factor,node,look,unit,signal,factor,reveal};
    this.stations.push(image);
    if (sprite.frames===1 && !reveal) this.image(ctx,image,0);
  }

  image(ctx,item,time) {
    const {sprite,look}=item;
    const phase=time%sprite.period/sprite.period;
    const frame=this.active(look)?Math.round((1-Math.abs(phase*2-1))*(sprite.frames-1)):0;
    let dx=0,dy=0;
    if(item.reveal){const amount=Math.max(0,1-(performance.now()-item.reveal.start)/180);dx=item.reveal.dx*amount;dy=item.reveal.dy*amount;}
    ctx.globalAlpha=look.never ? .45 : 1;
    ctx.drawImage(sprite.bitmap,frame*sprite.width,0,sprite.width,sprite.height,item.x+dx,item.y+dy,item.width,item.height);
    ctx.globalAlpha=1;
  }

  active(look) { return this.motion?.enabled && (look.rate>0&&this.motion.moving || look.replay || look.coverage==='selected'); }

  portrait(node,look,host) {
    const role=node.kind==='step'&&look.symbol==='branch'?'branch':node.kind;
    const sprite=this.assets.sprites.get(look.symbol)||this.assets.sprites.get(role)||this.assets.sprites.get('step'),b=sprite.bounds;
    const canvas=document.createElement('canvas'),ratio=Math.min(2,devicePixelRatio||1);
    canvas.width=canvas.height=112*ratio;canvas.style.width=canvas.style.height='112px';
    canvas.setAttribute('aria-hidden','true');
    const width=b.right-b.x,height=b.bottom-b.y,factor=92/Math.max(1,width,height);
    if(width>0&&height>0)canvas.getContext('2d').drawImage(sprite.bitmap,b.x,b.y,width,height,
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
    const ctx=this.context,r=this.ratio,time=this.time;
    ctx.setTransform(1,0,0,1,0,0);ctx.clearRect(0,0,this.canvas.width,this.canvas.height);ctx.drawImage(this.ground,0,0);
    for(const run of this.runs){
      const {origin,length,unit,angle,look,phase}=run;
      this.projected(ctx,6*unit);ctx.translate(...origin);ctx.rotate(angle);
      const moving=look.power?this.motion.enabled&&this.motion.powered:this.active(look);
      const period=2400/(1+Math.log10(1+Math.max(0,look.rate||0)));
      const offset=(moving?time/Math.max(300,period)*32:0)+phase;
      ctx.save();ctx.beginPath();ctx.rect(0,(look.power?-2:-12)*unit,length,(look.power?4:24)*unit);ctx.clip();
      ctx.strokeStyle=look.power||look.selected?this.palette.selected:this.palette.tread;ctx.lineWidth=unit;
      const pitch=(look.power?160:32)*unit;
      ctx.beginPath();
      for(let x=((offset*unit)%pitch+pitch)%pitch-pitch;x<length;x+=pitch){
        if(look.power){ctx.moveTo(x,0);ctx.lineTo(x+50*unit,0);}
        else {ctx.moveTo(x,-12*unit);ctx.lineTo(x,12*unit);ctx.moveTo(x+12*unit,-3*unit);ctx.lineTo(x+17*unit,0);ctx.lineTo(x+12*unit,3*unit);}
      }
      ctx.stroke();ctx.restore();
    }
    for(const junction of this.junctions)this.paintJunction(ctx,junction);
    for(const route of this.routes){
      if(!this.active(route.look))continue;
      const speed=32/Math.max(.3,2.4/(1+Math.log10(1+Math.max(0,route.look.rate||0))));
      const offset=(time/1000*speed)%64;
      for(const segment of route.segments){
        const start=Math.ceil((segment.from-offset)/64)*64+offset;
        for(let d=start;d<segment.to;d+=64){
          const t=(d-segment.offset)/segment.length,a=segment.a,b=segment.b;
          this.parcel(ctx,a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,route.unit);
        }
      }
    }
    ctx.setTransform(1,0,0,1,0,0);ctx.drawImage(this.roofs,0,0);ctx.setTransform(r,0,0,r,0,0);
    for(const item of this.stations){
      if(item.sprite.frames>1||item.reveal)this.image(ctx,item,time);
      if(!item.look.never && (this.motion.powered||this.active(item.look))){
        const height=item.unit*3;
        ctx.globalAlpha=this.motion.enabled ? .25+.45*(.5+.5*Math.sin(time/(this.active(item.look)?500:1800))) : .3;
        ctx.fillStyle=item.signal;
        ctx.beginPath();ctx.arc(item.x+item.width*.5,item.y+item.height*.38,Math.max(1.2,height),0,Math.PI*2);ctx.fill();ctx.globalAlpha=1;
      }
    }
    for(const terminal of this.terminals)if(this.active(terminal.look)){
      this.projected(ctx);
      const phase=time%1800/1800,box=terminal.box;
      ctx.beginPath();ctx.ellipse(box.x+box.width/2,box.y+box.height/2,box.width*.19*(1-phase),box.height*.19*(1-phase),0,0,Math.PI*2);
      ctx.fillStyle=terminal.signal;ctx.globalAlpha=1-phase;ctx.fill();ctx.globalAlpha=1;
    }
    ctx.setTransform(1,0,0,1,0,0);
    const moving=this.motion.enabled && (this.motion.powered && (this.stations.some(item=>!item.look.never)||this.runs.some(run=>run.look.power))
      || this.runs.some(run=>this.active(run.look)) || this.stations.some(item=>this.active(item.look)) || this.terminals.some(item=>this.active(item.look)));
    this.canvas.dataset.animated=String(moving);
    this.canvas.dataset.stations=String(this.stations.length);
    this.canvas.dataset.routes=String(this.routes.length);
    this.canvas.dataset.scenery=String(this.sceneryCount);
    if(moving||this.stations.some(item=>item.reveal&&now-item.reveal.start<180))this.frame=requestAnimationFrame(next=>this.draw(next));
  }

  parcel(ctx,x,y,unit) {
    ctx.setTransform(this.ratio,0,0,this.ratio,0,0);
    const p=(dx,dy,z)=>this.point(x+dx*unit,y+dy*unit,z*unit);
    this.polygon(ctx,[p(-6,-6,18),p(6,-6,18),p(6,6,18),p(-6,6,18)],this.palette.cargoTop);
    this.polygon(ctx,[p(-6,6,18),p(6,6,18),p(6,6,6),p(-6,6,6)],this.palette.cargoLight);
    this.polygon(ctx,[p(-6,-6,18),p(-6,6,18),p(-6,6,6),p(-6,-6,6)],this.palette.cargoShade);
  }

  hit(x,y,kind) {
    for(let i=this.stations.length-1;i>=0;i--){
      const item=this.stations[i],sprite=item.sprite;
      if(kind&&item.node.kind!==kind)continue;
      const px=Math.floor((x-item.x)/item.width*sprite.width),py=Math.floor((y-item.y)/item.height*sprite.height);
      if(px>=0&&py>=0&&px<sprite.width&&py<sprite.height&&sprite.alpha[py*sprite.width+px]>24)return item.node;
    }
    return null;
  }

  dispose() {
    this.disposed=true;cancelAnimationFrame(this.frame);
    this.assets.sprites.forEach(sprite=>sprite.bitmap.close());this.assets.sprites.clear();
    for(const canvas of [this.canvas,this.ground,this.roofs])canvas.width=canvas.height=0;
    this.canvas.remove();this.runs=this.routes=this.stations=this.terminals=this.junctions=[];
    return this;
  }
}
