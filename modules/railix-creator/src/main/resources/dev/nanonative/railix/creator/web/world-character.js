"use strict";

/** A bounded triangle buffer. Model coordinates are x, elevation, ground-y. */
class RailixCharacterMesh {
  constructor() { this.data = new Float32Array(131072); this.clear(); }
  clear() { this.length = 0; this.transform = [0,0,1,1,1]; this.motion = [0,0,0,0]; }
  vertex(p, normal, color, material) {
    if (this.length + 14 > this.data.length) {
      if (this.data.length >= 8388608) throw Error('Character scene exceeds its geometry budget.');
      const larger = new Float32Array(this.data.length * 2); larger.set(this.data); this.data = larger;
    }
    const [x,y,sx,sy,sz] = this.transform;
    const n = [normal[0]/sx,normal[2]/sy,normal[1]/sz], magnitude = Math.hypot(...n) || 1;
    this.data.set([x+p[0]*sx,y+p[2]*sy,p[1]*sz,...n.map(v=>v/magnitude),...color,material,...this.motion],this.length);
    this.length += 14;
  }
  tri(a,b,c,color,material=0) {
    const u=b.map((v,i)=>v-a[i]),v=c.map((q,i)=>q-a[i]);
    const n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];
    for (const p of [a,b,c]) this.vertex(p,n,color,material);
  }
  quad(a,b,c,d,color,material=0) { this.tri(a,b,c,color,material);this.tri(a,c,d,color,material); }
  box(x,y,z,w,h,d,color,material=0) {
    const a=[x,y,z],b=[x+w,y,z],c=[x+w,y,z+d],e=[x,y,z+d];
    const A=[x,y+h,z],B=[x+w,y+h,z],C=[x+w,y+h,z+d],E=[x,y+h,z+d];
    this.quad(A,E,C,B,color,material);this.quad(a,A,B,b,color,material);
    this.quad(b,B,C,c,color,material);this.quad(c,C,E,e,color,material);this.quad(e,E,A,a,color,material);
  }
  cylinder(x,y,z,r,h,color,sides=10,top=r) {
    for(let i=0;i<sides;i++) {
      const a=i*Math.PI*2/sides,b=(i+1)*Math.PI*2/sides;
      const A=[x+Math.cos(a)*r,y,z+Math.sin(a)*r],B=[x+Math.cos(b)*r,y,z+Math.sin(b)*r];
      const C=[x+Math.cos(b)*top,y+h,z+Math.sin(b)*top],D=[x+Math.cos(a)*top,y+h,z+Math.sin(a)*top];
      this.quad(A,D,C,B,color);this.tri([x,y+h,z],D,C,color);
    }
  }
  housing(x,y,z,w,h,d,color,trim,cut=.22) {
    const rim=[[x+cut,z],[x+w-cut,z],[x+w,z+cut],[x+w,z+d-cut],[x+w-cut,z+d],[x+cut,z+d],[x,z+d-cut],[x,z+cut]];
    for(let i=0;i<rim.length;i++) {
      const a=rim[i],b=rim[(i+1)%rim.length],A=[a[0],y+h,a[1]],B=[b[0],y+h,b[1]];
      this.quad([a[0],y,a[1]],A,B,[b[0],y,b[1]],color);
      this.tri([x+w/2,y+h,z+d/2],B,A,color);
      if(trim)this.line(A,B,Math.min(w,d)*.045,.04,trim,1);
    }
  }
  line(a,b,width,height,color,material=0) {
    const dx=b[0]-a[0],dy=b[2]-a[2],length=Math.hypot(dx,dy)||1,px=-dy/length*width/2,py=dx/length*width/2;
    const A=[a[0]+px,a[1],a[2]+py],B=[b[0]+px,b[1],b[2]+py],C=[b[0]-px,b[1],b[2]-py],D=[a[0]-px,a[1],a[2]-py];
    this.quad(A,B,C,D,color,material);
    if(height) { const down=p=>[p[0],p[1]-height,p[2]];this.quad(A,down(A),down(B),B,color,material);this.quad(D,C,down(C),down(D),color,material); }
  }
}

/** Native cell-resolution drawing; RailixWorld still owns graph, camera, picking and editing. */
class RailixCharacter {
  constructor(host) {
    this.canvas=document.createElement('canvas');this.canvas.className='world-raster world-character';
    this.canvas.setAttribute('aria-hidden','true');
    const gl=this.gl=this.canvas.getContext('webgl2',{alpha:false,antialias:false,powerPreference:'low-power'});
    if(!gl) throw Error('The character renderer needs WebGL 2. Choose Renderer Canvas or CSS on this device.');
    this.fixed=new RailixCharacterMesh();this.moving=new RailixCharacterMesh();
    this.time=0;this.liveTime=0;this.last=performance.now();this.frames=[];this.submissions=[];this.lastReport=0;this.builds=0;
    this.resources=[];
    try {
      this.geometry=this.program(`#version 300 es
        precision highp float;
        layout(location=0) in vec3 position;layout(location=1) in vec3 normal;
        layout(location=2) in vec3 color;layout(location=3) in float material;layout(location=4) in vec4 motion;
        uniform mat3 projection;uniform vec2 origin;uniform vec2 viewport;uniform vec3 view;
        uniform float time;uniform float liveTime;uniform float live;uniform float power;uniform sampler2D routes;uniform vec3 ground;
        out vec3 tint;out float shade;out float surface;out vec3 worldPosition;
        void main() {
          vec3 p=position,n=normal;
          if(motion.x>.5&&motion.x<3.5) {
            int row=int(motion.y);vec4 info=texelFetch(routes,ivec2(0,row),0);
            float movingAmount=info.w>2.5?power:info.w>1.5?1.:info.w>.5?live:0.;
            float distance=mod(time*info.z*movingAmount+motion.z,info.x);
            if(motion.x>1.5&&movingAmount<.5){gl_Position=vec4(2.,2.,2.,1.);tint=color;shade=1.;surface=material;worldPosition=p;return;}
            for(int i=1;i<16;i++) {
              vec4 a=texelFetch(routes,ivec2(i,row),0),b=texelFetch(routes,ivec2(i+1,row),0);
              float length=b.z-a.z;if(length<=.00001)continue;
              if(distance<=b.z){vec2 dir=(b.xy-a.xy)/length;mat2 rotation=mat2(dir.x,dir.y,-dir.y,dir.x);
                p.xy=rotation*p.xy+mix(a.xy,b.xy,(distance-a.z)/length);n.xy=rotation*n.xy;break;}
            }
          } else if(motion.x>3.5&&motion.x<5.5) {
            float clock=motion.w>1.5?time:liveTime;
            float angle=motion.x<4.5?clock*.8:sin(clock*1.5)*.23;
            mat2 rotation=mat2(cos(angle),sin(angle),-sin(angle),cos(angle));
            p.xy=rotation*(p.xy-motion.yz)+motion.yz;n.xy=rotation*n.xy;
          } else if(motion.x>5.5&&motion.x<6.5) p.z+=mod((motion.z>1.5?time:liveTime)*motion.w+motion.y,2.6*motion.w);
          worldPosition=vec3(p.xy/ground.z+ground.xy,p.z/ground.z);
          p.xy=p.xy*view.x+view.yz;p.z*=view.x;
          vec3 screen=projection*p;vec2 xy=screen.xy+origin;
          gl_Position=vec4(xy.x/viewport.x*2.-1.,1.-xy.y/viewport.y*2.,-screen.z/(max(viewport.x,viewport.y)*8.),1.);
          tint=color;shade=.48+.52*max(0.,dot(n,normalize(vec3(-.5,-.7,1.))));surface=material;
        }`, `#version 300 es
        precision highp float;in vec3 tint;in float shade;in float surface;in vec3 worldPosition;
        uniform float time;uniform float density;out vec4 outputColor;
        void main() {
          vec3 c=tint*shade;
          if(surface>.5&&surface<1.5)c=tint*1.15;
          else if(surface>1.5&&surface<2.5)c=tint;
          else if(surface>4.5){
            float ribbon=pow(max(0.,sin(worldPosition.z*.17-time*.85)),12.);
            c=tint*(.2+.75*ribbon);
          }
          else if(surface>3.5){
            vec2 q=mod(worldPosition.xy,512.);vec2 edge=min(q,512.-q);
            float bus=min(abs(edge.x-20.),abs(edge.y-20.));
            float traces=min(abs(edge.x-38.),abs(edge.y-38.));
            float corner=abs(q.x-q.y);float pad=length(q-vec2(56.));
            float circuit=max(1.-smoothstep(2.,4.,bus),.5*(1.-smoothstep(1.5,3.,traces)));
            circuit=max(circuit,(1.-smoothstep(2.,4.,corner))*step(q.x,56.));
            circuit=max(circuit,1.-smoothstep(2.,3.,abs(pad-12.)));
            float footprint=max(fwidth(worldPosition.x),fwidth(worldPosition.y));
            float detail=1.-smoothstep(8.,24.,footprint);
            c=mix(tint,tint*2.2+vec3(.01,.09,.12),circuit*.8*detail);
          }
          else if(surface>2.5){float wave=sin(worldPosition.x*.045+time*.7)+sin(worldPosition.y*.06-time*.6);
            c=tint*(.9+.09*wave);c+=vec3(.13,.22,.18)*pow(max(0.,sin(worldPosition.x*.02+worldPosition.y*.075+time*.5)),28.);}
          float curve=(surface>1.5&&surface<2.5)||(surface>3.5&&surface<4.5)?1.:density;
          float glyph=clamp(floor(pow(max(0.,dot(c,vec3(.2126,.7152,.0722))),curve)*11.),0.,8.);
          // Pack the glyph and emissive flag into the existing 8-bit alpha channel.
          float emission=(surface>.5&&surface<1.5)||surface>4.5?16.:0.;
          outputColor=vec4(c,(glyph+emission)/255.);
        }`);
      this.composite=this.program(`#version 300 es
        precision highp float;out vec2 uv;
        void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=p;gl_Position=vec4(p*2.-1.,0.,1.);}`, `#version 300 es
        precision highp float;in vec2 uv;out vec4 outputColor;
        uniform sampler2D picture;uniform sampler2D alphabet;uniform vec2 grid;
        uniform float background;uniform float pixels;uniform float glow;uniform vec3 paper;
        vec3 lightAt(vec2 at){vec4 s=texture(picture,at);return s.rgb*step(15.5,floor(s.a*255.+.5));}
        void main(){vec2 cell=floor(uv*grid);vec4 sampleColor=texture(picture,(cell+.5)/grid);vec3 c=sampleColor.rgb;
          vec3 spill=vec3(0.);vec2 center=(cell+.5)/grid;
          if(glow>0.) {
            spill=(lightAt(center+vec2(1.,0.)/grid)+lightAt(center-vec2(1.,0.)/grid)
              +lightAt(center+vec2(0.,1.)/grid)+lightAt(center-vec2(0.,1.)/grid))*.22*glow;
          }
          if(pixels>.5){outputColor=vec4(min(c+spill,vec3(1.)),1.);return;}
          float glyph=mod(floor(sampleColor.a*255.+.5),16.);
          if(dot(spill,vec3(.2126,.7152,.0722))>.025)glyph=max(glyph,3.);
          vec2 local=fract(uv*grid);float ink=texture(alphabet,vec2((glyph+local.x)/9.,1.-local.y)).r;
          if(glow>0.&&glyph<.5)ink=0.;
          vec3 base=mix(paper,c,background)+spill*background;
          outputColor=vec4(mix(base,min(c*1.75+.025+spill*1.8,vec3(1.)),ink),1.);
        }`);
      const uniforms=(program,names)=>Object.fromEntries(names.map(name=>[name,gl.getUniformLocation(program,name)]));
      this.gu=uniforms(this.geometry,['projection','origin','viewport','view','time','liveTime','live','power','routes','ground','density']);
      this.su=uniforms(this.composite,['picture','alphabet','grid','background','pixels','paper','glow']);
      this.fixedBuffer=this.buffer();this.movingBuffer=this.buffer();this.empty=this.create('VertexArray');
      this.routesTexture=this.create('Texture');this.alphabet=this.create('Texture');this.picture=this.create('Texture');
      this.framebuffer=this.create('Framebuffer');this.depth=this.create('Renderbuffer');
      const glyphs=['00000000000000000000000000010000000','00000000000010000000000000010000000','00000001000000000100000000010000000','00000000001111100000111110000000000','00000001000010011111001000010000000','00000101010111011111011101010100000','01010010101111101010111110101001010','11111100011010110111101011000111111','11111101011111110101111111010111111'];
      const bitmap=new Uint8Array(63*9*4);
      glyphs.forEach((glyph,index)=>[...glyph].forEach((bit,i)=>{const at=((Math.floor(i/5)+1)*63+index*7+i%5+1)*4;bitmap[at]=bitmap[at+1]=bitmap[at+2]=bit==='1'?255:0;bitmap[at+3]=255;}));
      gl.bindTexture(gl.TEXTURE_2D,this.alphabet);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,63,9,0,gl.RGBA,gl.UNSIGNED_BYTE,bitmap);this.textureSettings();
      this.onLost=event=>{
        event.preventDefault();cancelAnimationFrame(this.frame);this.lost=true;
        this.notice=document.createElement('p');this.notice.className='world-renderer-error';this.notice.setAttribute('role','alert');
        this.notice.textContent='Graphics context lost. Choose another renderer in Settings.';host.append(this.notice);
      };
      this.canvas.addEventListener('webglcontextlost',this.onLost);
      host.append(this.canvas);
    } catch(error) { this.dispose();throw error; }
  }

  create(type) { const resource=this.gl['create'+type]();this.resources.push([type,resource]);return resource; }
  program(vertex,fragment) {
    const gl=this.gl,program=this.create('Program');
    for(const [type,source] of [[gl.VERTEX_SHADER,vertex],[gl.FRAGMENT_SHADER,fragment]]) {
      const shader=gl.createShader(type);gl.shaderSource(shader,source);gl.compileShader(shader);
      const message=gl.getShaderParameter(shader,gl.COMPILE_STATUS)?'':gl.getShaderInfoLog(shader);
      if(message){gl.deleteShader(shader);throw Error(message);}
      gl.attachShader(program,shader);gl.deleteShader(shader);
    }
    gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program));return program;
  }
  buffer() {
    const gl=this.gl,vao=this.create('VertexArray'),buffer=this.create('Buffer');
    gl.bindVertexArray(vao);gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    for(const [i,size,offset] of [[0,3,0],[1,3,12],[2,3,24],[3,1,36],[4,4,40]]) {
      gl.enableVertexAttribArray(i);gl.vertexAttribPointer(i,size,gl.FLOAT,false,56,offset);
    }
    return {vao,buffer};
  }
  textureSettings() {
    const gl=this.gl;gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  }
  begin(size,projection,origin,theme,camera={x:0,y:0,scale:1},unit=1) {
    this.fixed.clear();this.moving.clear();this.paths=[];this.stations=[];this.pending=true;
    this.palette={};
    const defaults={ground:'#1b302c',edge:'#172c30',metal:'#5c7872',cream:'#d1dbb9',roof:'#a4b9a4',dark:'#193739',mint:'#a2e4b9',teal:'#529f9c',copper:'#d2a773',warning:'#f4af69',error:'#dd7367'};
    for(const [name,fallback] of Object.entries(defaults)) {
      const token=theme.getPropertyValue('--character-'+name).trim(),value=/^#[\da-f]{6}$/i.test(token)?token:fallback;
      this.palette[name]=[1,3,5].map(i=>parseInt(value.slice(i,i+2),16)/255);
    }
    this.cell=Math.max(2,Math.min(16,Number(theme.getPropertyValue('--character-cell'))||4));
    this.background=Math.max(0,Math.min(1,Number(theme.getPropertyValue('--character-background'))||0));
    this.pixels=Number(theme.getPropertyValue('--character-pixels'))||0;
    this.density=Math.max(.3,Math.min(2,Number(theme.getPropertyValue('--character-density'))||1));
    this.glow=Math.max(0,Math.min(1,Number(theme.getPropertyValue('--character-glow'))||0));
    this.circuit=Number(theme.getPropertyValue('--character-circuit'))===1;
    this.ground=[camera.x/unit,camera.y/unit,camera.scale*unit];
    this.camera(size,projection,origin,1,0,0);
    const extent=Math.max(size.width,size.height)*4;
    this.fixed.box(-extent,this.circuit?-3:-82,-extent,extent*3,1,extent*3,this.palette.ground,this.circuit?4:0);
    return this;
  }
  camera(size,projection,origin,scale,dx,dy) {
    this.size=size;this.projection=projection;this.origin=origin;this.view=[scale,dx,dy];return this;
  }
  point(x,y,z=0) {
    const p=this.projection,[scale,dx,dy]=this.view;x=x*scale+dx;y=y*scale+dy;z*=scale;
    return [this.origin[0]+p.m11*x+p.m21*y+p.m31*z,this.origin[1]+p.m12*x+p.m22*y+p.m32*z];
  }
  activity(look) { return look.power?3:look.replay||look.coverage==='selected'?2:look.rate>0?1:0; }
  path(points,unit,look) {
    const row=this.paths.length,record=new Float32Array(17*4);let length=0;
    if(points.length>16)throw Error('Character transport exceeds its route point budget.');
    points.forEach((point,i)=>{if(i)length+=Math.hypot(point[0]-points[i-1][0],point[1]-points[i-1][1]);record.set([...point,length,0],(i+1)*4);});
    record.set([length,unit,32*unit/Math.max(.3,2.4/(1+Math.log10(1+Math.max(0,look.rate||0)))),this.activity(look)]);
    this.paths.push(record);return {row,length};
  }
  run({origin,length,unit,angle,look,phase}) {
    const m=this.fixed,c=this.palette,dir=[Math.cos(angle),Math.sin(angle)],end=origin.map((v,i)=>v+dir[i]*length);
    const a=[origin[0],6*unit,origin[1]],b=[end[0],6*unit,end[1]],width=(look.power?8:30)*unit;
    m.line(a,b,width,4*unit,c.dark);
    for(const side of [-1,1]) {
      const offset=[-dir[1]*width/2*side,dir[0]*width/2*side];
      m.line([a[0]+offset[0],7*unit,a[2]+offset[1]],[b[0]+offset[0],7*unit,b[2]+offset[1]],(this.circuit?5:3)*unit,2*unit,look.selected?c.mint:c.teal,this.circuit?1:0);
    }
    const path=this.path([origin,end],unit,look),moving=this.moving;
    const pitch=(look.power?160:32)*unit;
    for(let d=0;d<length;d+=pitch) {
      moving.motion=[look.power?3:1,path.row,(d+phase*unit+length)%length,0];
      moving.box(0,7.1*unit,look.power?-unit:-12*unit,(look.power?30:6)*unit,unit,look.power?2*unit:24*unit,look.power?c.mint:this.circuit?c.metal:c.roof,look.power?1:0);
    }
    moving.motion=[0,0,0,0];
  }
  junction(point,mask,look,unit) {
    const c=this.palette,m=this.fixed,h=15*unit;
    m.box(point[0]-h,7*unit,point[1]-h,2*h,unit,2*h,c.dark);
    for(const [bit,a,b] of [[1,[-1,-1],[1,-1]],[2,[1,-1],[1,1]],[4,[1,1],[-1,1]],[8,[-1,1],[-1,-1]]]) {
      if(!(mask&bit))m.line([point[0]+a[0]*h,8.1*unit,point[1]+a[1]*h],[point[0]+b[0]*h,8.1*unit,point[1]+b[1]*h],(this.circuit?5:3)*unit,unit,look.selected?c.mint:c.teal,this.circuit?1:0);
    }
  }
  route({look,unit,points,segments}) {
    if(!segments.length)return;
    // Retain complete route distance. Parcels loop beneath machines, not at clipped elbows.
    const {row,length}=this.path(points,unit,look),m=this.moving,c=this.palette;
    for(let d=0;d<length;d+=80*unit) {
      m.motion=[2,row,d,0];m.box(-7*unit,8*unit,-7*unit,14*unit,10*unit,14*unit,this.circuit?c.metal:c.copper);
      m.box(-5*unit,18*unit,-2*unit,10*unit,.6*unit,4*unit,this.circuit?c.mint:c.cream,1);
      if(this.circuit)m.box(-7.1*unit,10*unit,-4*unit,.2*unit,5*unit,8*unit,c.mint,1);
    }
    m.motion=[0,0,0,0];
  }
  station({node,box,look},contour,unit) {
    const m=this.fixed,c=this.palette,cx=box.x+box.width/2,cy=box.y+box.height/2;
    const signal=look.error?c.error:look.warning?c.warning:look.never?c.metal:c.mint;
    if(node.expanded) {
      for(let i=0;i<contour.length;i++){const a=contour[i],b=contour[(i+1)%contour.length];m.line([a[0],.1,a[1]],[b[0],.1,b[1]],1,0,signal,1);}return;
    }
    const station={node,box,look,unit};this.stations.push(station);
    if(!this.circuit)m.quad([box.x,-.1,box.y],[box.x+box.width+35*unit,-.1,box.y+20*unit],[box.x+box.width+35*unit,-.1,box.y+box.height+20*unit],[box.x,-.1,box.y+box.height],c.edge,2);
    if(look.selected || look.related || look.editing) {
      const b={x:box.x-8*unit,y:box.y-8*unit,width:box.width+16*unit,height:box.height+16*unit};
      const points=[[b.x,b.y],[b.x+b.width,b.y],[b.x+b.width,b.y+b.height],[b.x,b.y+b.height]];
      for(let i=0;i<4;i++){const a=points[i],p=points[(i+1)%4];m.line([a[0],.15,a[1]],[p[0],.15,p[1]],2*unit,0,signal,1);}
    }
    const role=node.kind==='step'&&look.symbol==='branch'?'branch':node.kind;
    const body=look.error?c.error:look.warning?c.warning:look.changed?c.copper:look.never?c.metal:c.cream;
    m.transform=[cx,cy,box.width/4,box.height/4,18*unit];
    station.start=m.length;
    if(Math.min(box.width,box.height)<12)m.box(-1.9,0,-1.9,3.8,1,3.8,body);
    else if(this.circuit)this.circuitBuilding(m,role,look.never?c.dark:c.metal,signal);
    else this.building(m,role,body,signal);
    station.end=m.length;
    if(this.circuit) {
      // Project the actual casing silhouette once, not a separate shadow pass each frame.
      const transform=m.transform;m.transform=[0,0,1,1,1];
      for(let i=station.start;i<station.end;i+=42) {
        const points=[i,i+14,i+28].map(at=>[m.data[at]+m.data[at+2]*.5,-.1,m.data[at+1]+m.data[at+2]*.7]);
        m.tri(...points,c.edge,2);
      }
      m.transform=transform;
    }
    const activity=this.activity(look);
    if(activity&&Math.min(box.width,box.height)>=18) {
      const moving=this.moving;moving.transform=m.transform;
      if(role==='trigger')for(let i=0;i<4;i++){moving.motion=[6,i*12*unit,activity,18*unit];moving.box(-.04,.45,-1,.08,.09,2,this.circuit?signal:c.teal,1);}
      if(role==='branch'){moving.motion=[5,cx,cy,activity];moving.line([-.8,1.08,0],[.7,1.08,-.7],.28,.12,this.circuit?signal:c.copper,this.circuit?1:0);}
      if(role==='step'){moving.motion=[4,cx,cy,activity];for(let i=0;i<3;i++){const angle=i*2.094;moving.line([0,3.08,0],[Math.cos(angle)*.63,3.08,Math.sin(angle)*.63],.13,.06,c.cream);}}
      moving.transform=[0,0,1,1,1];moving.motion=[0,0,0,0];
    }
    m.transform=[0,0,1,1,1];
  }
  building(m,role,body,signal) {
    const c=this.palette;
    if(role==='app') {
      m.box(-2,0,-1.8,4,.6,3.6,c.dark);m.box(-1.6,.6,-1.45,3.2,2.9,2.9,body);m.box(-1.85,3.5,-1.65,3.7,.35,3.3,c.roof);
      m.box(-1.25,2.1,-1.49,2.5,.65,.08,c.teal,1);m.box(-1.65,2.1,-.9,.08,.65,1.8,c.teal,1);
      m.box(-.3,3.85,-.3,.6,1.1,.6,c.dark);m.box(-.07,4.9,-.07,.14,1,.14,signal,1);
    } else if(role==='trigger') {
      m.box(-1.1,0,-1.65,2.2,.35,3.3,c.dark);
      for(const side of [-1,1]){m.box(-.45,.35,side*1.25-.25,.9,3.2,.5,body);m.box(-.49,.5,side*1.25-.11,.98,2.7,.22,signal,1);}
      m.box(-.55,3.45,-1.55,1.1,.45,3.1,body);m.box(-.57,3.45,-1.1,1.14,.12,2.2,signal,1);
    } else if(role==='region') {
      m.box(-2,0,-1.9,4,.5,3.8,c.dark);m.box(-1.8,.5,-1.75,3.6,.4,3.5,c.metal);
      for(let i=0;i<3;i++){m.box(-1.45+i,.9,-.9,.55,.65,1.7,c.roof);m.box(-1.4+i,1.5,-.6,.4,.07,.6,signal,1);}
      for(const side of [-1,1]){m.box(-1.8,.9,side*1.55-.1,3.6,1.5,.2,body);m.box(-1.95,2.4,side*1.55-.16,3.9,.22,.32,signal);}
      for(let j=0;j<3;j++)m.box(-1.65+j*1.65,2.6,-1.6,.12,.16,3.2,c.roof);
      m.box(-1.9,2.72,.5,3.8,.14,1.15,c.teal);m.box(-1.9,2.72,-1.5,3.8,.14,.4,c.teal);
    } else if(role==='branch') {
      m.box(-1.8,0,-.75,2.5,.45,1.5,c.dark);m.box(.4,0,-2,1.3,.45,4,c.dark);
      m.box(-1.7,.45,-.65,2.4,.45,1.3,body);m.box(.45,.45,-1.9,1.15,.45,3.8,body);m.cylinder(.7,.9,0,.48,.3,c.metal);
      for(const side of [-1,1])m.box(.48,.91,side*1.4-.12,1.1,.09,.24,signal,1);
    } else if(role==='end') {
      for(const side of [-1,1]){m.box(-1.45,0,side*1.25-.18,2.9,.6,.36,body);m.box(side*1.25-.18,0,-1.1,.36,.6,2.2,body);}
      m.box(-1.05,.025,-1.05,2.1,.06,2.1,c.dark);m.box(-.95,.11,-.95,1.9,.03,1.9,c.edge);
    } else {
      m.box(-1.5,0,-1.35,3,.55,2.7,c.dark);m.box(-1.3,.55,-1.2,2.6,1.65,2.4,body);m.box(-1.5,2.2,-1.3,3,.2,2.6,c.roof);
      m.cylinder(0,2.4,0,.78,.45,c.dark);m.cylinder(0,2.85,0,.58,.18,signal);
      for(let i=0;i<4;i++)m.box(-1+i*.55,1.1,-1.24,.18,.65,.09,c.dark);
      m.box(-1.31,1.65,-.75,.08,.18,1.5,signal,1);
    }
  }
  circuitBuilding(m,role,body,signal) {
    const c=this.palette;
    const frame=(x,y,z,w,d)=>m.housing(x,y,z,w,.12,d,c.dark,signal);
    const pins=(y,z,length)=>{for(let i=0;i<6;i++)m.box(-1.4+i*.52,y,z,.2,.12,length,c.roof);};
    if(role==='branch') {
      m.housing(-1.95,0,-.68,2.65,.6,1.36,body,signal);
      m.housing(.42,0,-1.95,1.45,.6,3.9,body,signal);
      m.line([-1.7,.65,0],[.7,.65,0],.48,0,c.dark);
      m.line([.85,.65,-1.7],[.85,.65,1.7],.55,0,c.dark);
      m.cylinder(.45,.65,0,.52,.35,c.dark,8);m.cylinder(.45,1,0,.23,.12,signal,8);
      for(const z of [-1.65,1.65])m.box(.5,.65,z-.08,.65,.12,.16,signal,1);
      return;
    }
    if(role==='end') {
      frame(-1.65,0,-1.65,3.3,3.3);frame(-1.32,.13,-1.32,2.64,2.64);
      m.housing(-1.05,.26,-1.05,2.1,.04,2.1,c.edge,null);
      for(const z of [-1,1])m.box(-.5,.32,z*1.25-.04,1,.04,.08,signal,1);
      return;
    }
    const width=role==='trigger'?2.1:3.8;
    m.housing(-width/2,0,-1.8,width,.32,3.6,c.dark,c.teal);
    if(role==='trigger') {
      for(const z of [-1.35,1.35]){
        m.housing(-.45,.32,z-.3,.9,2.95,.6,body,signal,.14);
        m.box(-.18,.5,z-.36,.36,2.65,.72,c.dark);
        for(const face of [-1,1])m.box(-.22,.65,z+face*.37,.44,2.3,.04,signal,1);
      }
      m.housing(-.5,3.27,-1.65,1,.25,3.3,body,signal,.15);
      m.quad([0,.35,-1.02],[0,3.25,-1.02],[0,3.25,1.02],[0,.35,1.02],c.teal,5);
      m.line([-.65,.36,-1.1],[.65,.36,-1.1],.08,0,signal,1);
      m.line([-.65,.36,1.1],[.65,.36,1.1],.08,0,signal,1);
    } else if(role==='app') {
      m.housing(-1.5,.32,-1.4,3,1.8,2.8,body,c.teal);
      m.housing(-1.25,2.12,-1.2,2.5,1.45,2.4,c.dark,signal);
      m.box(-1.26,2.32,-.85,.04,.7,1.7,c.teal,1);
      m.box(-.85,2.32,-1.21,1.7,.7,.04,c.teal,1);
      m.housing(-.85,3.57,-.85,1.7,.22,1.7,body,signal);
      m.cylinder(0,3.8,0,.23,.7,c.dark,6);m.cylinder(0,4.5,0,.12,.3,signal,6);
      for(const z of [-1.6,1.4])pins(.34,z,.24);
    } else if(role==='region') {
      m.housing(-1.7,.32,-1.58,3.4,.35,3.16,body,c.teal);
      for(let i=0;i<3;i++){
        m.housing(-1.35+i,.67,-1.05,.7,.65,2.1,c.dark,signal,.12);
        m.box(-1.16+i,1.33,-.75,.3,.08,1.5,c.teal,1);
      }
      for(const z of [-1.5,1.5]){
        m.box(-1.7,.67,z-.08,3.4,1.32,.16,body);
        m.line([-1.65,2,z],[1.65,2,z],.16,.05,signal,1);
      }
      // An open vault makes the contained machines visible without extra Step models.
      for(const x of [-1.55,1.55]){
        m.line([x,2,-1.5],[x,2.65,-.6],.15,.07,signal,1);
        m.line([x,2.65,-.6],[x,2.65,.6],.15,.07,signal,1);
        m.line([x,2.65,.6],[x,2,1.5],.15,.07,signal,1);
      }
    } else {
      pins(.34,-1.7,.35);pins(.34,1.35,.35);
      m.housing(-1.38,.45,-1.23,2.76,1.75,2.46,body,c.teal);
      m.housing(-1.55,2.2,-1.35,3.1,.25,2.7,c.dark,signal);
      m.cylinder(0,2.45,0,.8,.35,c.dark,10);m.cylinder(0,2.8,0,.65,.22,c.teal,10);
      for(let i=0;i<5;i++)m.box(-1.2+i*.52,.8,-1.26,.16,1.1,.05,c.dark);
      m.box(-1.4,1,-.7,.04,.18,1.4,signal,1);
    }
  }
  upload() {
    const gl=this.gl;
    for(const [mesh,target] of [[this.fixed,this.fixedBuffer],[this.moving,this.movingBuffer]]) {
      gl.bindBuffer(gl.ARRAY_BUFFER,target.buffer);gl.bufferData(gl.ARRAY_BUFFER,mesh.data.subarray(0,mesh.length),gl.STATIC_DRAW);
    }
    const routes=new Float32Array(Math.max(1,this.paths.length)*68);this.paths.forEach((row,i)=>routes.set(row,i*68));
    gl.activeTexture(gl.TEXTURE2);gl.bindTexture(gl.TEXTURE_2D,this.routesTexture);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA32F,17,Math.max(1,this.paths.length),0,gl.RGBA,gl.FLOAT,routes);this.textureSettings();
    this.canvas.dataset.stations=String(this.stations.length);this.canvas.dataset.routes=String(this.paths.length);
    this.canvas.dataset.geometryBuilds=String(++this.builds);
    this.canvas.dataset.ground=JSON.stringify(this.ground);
    this.pending=false;
  }
  resize() {
    const gl=this.gl,{width,height}=this.size,ratio=Math.min(2,devicePixelRatio||1,Math.sqrt(8_000_000/(width*height)));
    const columns=Math.max(1,Math.floor(width/this.cell)),rows=Math.max(1,Math.floor(height/(this.cell*9/7)));
    if(this.canvas.width!==Math.round(width*ratio)||this.canvas.height!==Math.round(height*ratio)) {
      this.canvas.width=Math.round(width*ratio);this.canvas.height=Math.round(height*ratio);
      this.canvas.style.width=width+'px';this.canvas.style.height=height+'px';
    }
    if(columns===this.columns&&rows===this.rows)return;
    this.columns=columns;this.rows=rows;
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,this.picture);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,columns,rows,0,gl.RGBA,gl.UNSIGNED_BYTE,null);this.textureSettings();
    gl.bindRenderbuffer(gl.RENDERBUFFER,this.depth);gl.renderbufferStorage(gl.RENDERBUFFER,gl.DEPTH_COMPONENT16,columns,rows);
    gl.bindFramebuffer(gl.FRAMEBUFFER,this.framebuffer);
    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,this.picture,0);
    gl.framebufferRenderbuffer(gl.FRAMEBUFFER,gl.DEPTH_ATTACHMENT,gl.RENDERBUFFER,this.depth);
    if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE)throw Error('Character framebuffer is unavailable.');
  }
  animate(motion) {
    if(this.motion?.enabled!==motion.enabled){this.sample=false;this.last=performance.now();}
    this.motion=motion;
    if(!this.size||this.disposed||this.lost)return this;
    if(!motion.enabled){cancelAnimationFrame(this.frame);this.frame=0;}
    if(!this.frame)this.draw(performance.now());return this;
  }
  draw(now) {
    this.frame=0;if(this.disposed||this.lost)return;
    const start=performance.now(),elapsed=now-this.last,consecutive=this.sample;this.last=now;
    this.sample=this.motion.enabled&&!document.hidden;
    if(this.motion.enabled)this.time+=Math.min(100,elapsed)/1000;
    if(this.motion.enabled&&this.motion.moving)this.liveTime+=Math.min(100,elapsed)/1000;
    if(this.pending)this.upload();this.resize();
    const gl=this.gl,m=this.projection,u=this.gu,c=this.palette;
    gl.bindFramebuffer(gl.FRAMEBUFFER,this.framebuffer);gl.viewport(0,0,this.columns,this.rows);
    gl.enable(gl.DEPTH_TEST);gl.disable(gl.BLEND);gl.clearColor(...c.edge,0);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
    gl.useProgram(this.geometry);
    gl.uniformMatrix3fv(u.projection,false,[m.m11,m.m12,m.m13,m.m21,m.m22,m.m23,m.m31,m.m32,m.m33]);
    gl.uniform2fv(u.origin,this.origin);gl.uniform2f(u.viewport,this.size.width,this.size.height);gl.uniform3fv(u.view,this.view);
    gl.uniform3fv(u.ground,this.ground);
    gl.uniform1f(u.density,this.density);
    gl.uniform1f(u.time,this.time);gl.uniform1f(u.liveTime,this.liveTime);gl.uniform1f(u.live,this.motion.moving?1:0);gl.uniform1f(u.power,this.motion.powered?1:0);
    gl.activeTexture(gl.TEXTURE2);gl.bindTexture(gl.TEXTURE_2D,this.routesTexture);gl.uniform1i(u.routes,2);
    gl.bindVertexArray(this.fixedBuffer.vao);gl.drawArrays(gl.TRIANGLES,0,this.fixed.length/14);
    gl.bindVertexArray(this.movingBuffer.vao);gl.drawArrays(gl.TRIANGLES,0,this.moving.length/14);
    const s=this.su;gl.bindFramebuffer(gl.FRAMEBUFFER,null);gl.viewport(0,0,this.canvas.width,this.canvas.height);
    gl.disable(gl.DEPTH_TEST);gl.useProgram(this.composite);
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,this.picture);gl.uniform1i(s.picture,0);
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,this.alphabet);gl.uniform1i(s.alphabet,1);
    gl.uniform2f(s.grid,this.columns,this.rows);gl.uniform1f(s.background,this.background);gl.uniform1f(s.pixels,this.pixels);gl.uniform3fv(s.paper,c.edge);
    gl.uniform1f(s.glow,this.glow);
    gl.bindVertexArray(this.empty);gl.drawArrays(gl.TRIANGLES,0,3);
    if(this.portraitRequest)this.paintPortrait();
    if(elapsed>0&&consecutive){this.frames.push(elapsed);this.submissions.push(performance.now()-start);if(this.frames.length>300){this.frames.shift();this.submissions.shift();}}
    if(now-this.lastReport>=1000) {
      const percentile=(values,p)=>values.length?values.toSorted((a,b)=>a-b)[Math.min(values.length-1,Math.floor(values.length*p))]:0;
      this.canvas.dataset.measurements=JSON.stringify({frameMedian:percentile(this.frames,.5),frameP95:percentile(this.frames,.95),frameMax:percentile(this.frames,1),submissionMedian:percentile(this.submissions,.5),samples:this.frames.length,triangles:(this.fixed.length+this.moving.length)/42,cells:this.columns*this.rows,builds:this.builds});
      this.lastReport=now;
    }
    this.canvas.dataset.animated=String(this.motion.enabled);
    if(this.motion.enabled&&!document.hidden)this.frame=requestAnimationFrame(time=>this.draw(time));
  }
  hit(x,y,kind) {
    let result=null,depth=-Infinity;
    for(const station of this.stations) {
      if(kind&&station.node.kind!==kind)continue;
      const {box,unit}=station,height=station.node.kind==='app'?106:station.node.kind==='trigger'?72:56;
      const corners=[[box.x,box.y],[box.x+box.width,box.y],[box.x,box.y+box.height],[box.x+box.width,box.y+box.height]].flatMap(p=>[this.point(...p),this.point(...p,height*unit)]);
      const left=Math.min(...corners.map(p=>p[0])),top=Math.min(...corners.map(p=>p[1])),right=Math.max(...corners.map(p=>p[0])),bottom=Math.max(...corners.map(p=>p[1]));
      if(x<left||x>right||y<top||y>bottom)continue;
      // Triangle picking only for broad-phase candidates; no GPU readback or per-frame pick pass.
      const mesh=this.fixed.data,p=this.projection;
      for(let i=station.start;i<station.end;i+=42) {
        const a=this.point(mesh[i],mesh[i+1],mesh[i+2]),b=this.point(mesh[i+14],mesh[i+15],mesh[i+16]),c=this.point(mesh[i+28],mesh[i+29],mesh[i+30]);
        const determinant=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]);
        if(Math.abs(determinant)<.001)continue;
        const u=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/determinant;
        const v=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/determinant;
        if(u<0||v<0||u+v>1)continue;
        const z=offset=>p.m13*mesh[offset]+p.m23*mesh[offset+1]+p.m33*mesh[offset+2];
        const at=u*z(i)+v*z(i+14)+(1-u-v)*z(i+28);
        if(at>depth){depth=at;result=station.node;}
      }
    }
    return result;
  }
  portrait(node,look,host) { this.portraitRequest={node,host};if(!this.frame&&this.size)this.draw(performance.now());return this; }
  paintPortrait() {
    const {node,host}=this.portraitRequest,item=this.stations.find(station=>station.node.id===node.id);
    if(!host.isConnected){this.portraitRequest=null;return;}
    if(!item)return;
    const canvas=host.firstElementChild?.tagName==='CANVAS'?host.firstElementChild:document.createElement('canvas');
    if(canvas.parentElement!==host){canvas.width=canvas.height=224;canvas.style.width=canvas.style.height='112px';host.replaceChildren(canvas);}
    const context=canvas.getContext('2d'),mesh=this.fixed.data,points=[];
    for(let i=item.start;i<item.end;i+=14)points.push(this.point(mesh[i],mesh[i+1],mesh[i+2]));
    const left=Math.min(...points.map(p=>p[0])),right=Math.max(...points.map(p=>p[0]));
    const top=Math.min(...points.map(p=>p[1])),bottom=Math.max(...points.map(p=>p[1]));
    const side=Math.max(right-left,bottom-top)*1.08,x=(left+right-side)/2,y=(top+bottom-side)/2,r=this.canvas.width/this.size.width;
    context.clearRect(0,0,224,224);context.imageSmoothingEnabled=false;context.save();context.beginPath();
    // Clip to this building's triangles, excluding neighboring machines from the portrait.
    for(let i=0;i<points.length;i+=3) {
      const [a,b,c]=points.slice(i,i+3).map(p=>[(p[0]-x)*224/side,(p[1]-y)*224/side]);
      const order=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])<0?[a,c,b]:[a,b,c];
      context.moveTo(...order[0]);context.lineTo(...order[1]);context.lineTo(...order[2]);context.closePath();
    }
    context.clip();context.drawImage(this.canvas,x*r,y*r,side*r,side*r,0,0,224,224);context.restore();this.portraitRequest=null;
  }
  dispose() {
    this.disposed=true;cancelAnimationFrame(this.frame);
    this.canvas.removeEventListener('webglcontextlost',this.onLost);
    this.notice?.remove();
    for(const [type,resource] of this.resources)this.gl['delete'+type](resource);
    this.resources=[];this.gl.getExtension('WEBGL_lose_context')?.loseContext();this.canvas.width=this.canvas.height=0;this.canvas.remove();
    return this;
  }
}
