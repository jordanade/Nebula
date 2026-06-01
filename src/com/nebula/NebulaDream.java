package com.nebula;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.service.dreams.DreamService;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class NebulaDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        GLSurfaceView sv = new GLSurfaceView(this);
        sv.setEGLContextClientVersion(2);
        sv.setRenderer(new NebulaRenderer());
        sv.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(sv);
    }

    static class NebulaRenderer implements GLSurfaceView.Renderer {

        private static final float[] QUAD = {
            -1f,-1f,  1f,-1f,  -1f,1f,
             1f,-1f,  1f, 1f,  -1f,1f
        };

        private static final String VERT =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUv;\n" +
            "void main(){\n" +
            "  vUv=aPos*0.5+0.5;\n" +
            "  gl_Position=vec4(aPos,0.0,1.0);\n" +
            "}\n";

        private static final String FRAG =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "  precision highp float;\n" +
            "#else\n" +
            "  precision mediump float;\n" +
            "#endif\n" +
            "uniform float uTime;\n" +
            "uniform vec2  uRes;\n" +
            "varying vec2  vUv;\n" +

            // ── Hash ──────────────────────────────────────────────────────────
            "vec2 h2(vec2 i){\n" +
            "  vec2 p=fract(i*vec2(0.1031,0.1030));\n" +
            "  p+=dot(p,p.yx+19.19);\n" +
            "  return fract((p.xx+p.yx)*p.xy);\n" +
            "}\n" +
            "float h1(vec2 i){\n" +
            "  vec2 p=fract(i*vec2(0.1031,0.1030));\n" +
            "  p+=dot(p,p+19.19);\n" +
            "  return fract(p.x*p.y);\n" +
            "}\n" +

            // ── Value noise ───────────────────────────────────────────────────
            "float vn(vec2 p){\n" +
            "  vec2 i=floor(p),f=fract(p),u=f*f*(3.0-2.0*f);\n" +
            "  return mix(mix(h1(i),h1(i+vec2(1,0)),u.x),\n" +
            "             mix(h1(i+vec2(0,1)),h1(i+vec2(1,1)),u.x),u.y);\n" +
            "}\n" +

            // ── Smooth FBM — 4 octaves (was 5) ───────────────────────────────
            "float sfbm(vec2 p){\n" +
            "  float v=0.0,a=0.55;\n" +
            "  mat2 rot=mat2(0.8,-0.6,0.6,0.8);\n" +
            "  for(int i=0;i<4;i++){\n" +
            "    v+=a*(vn(p)*2.0-1.0); p=rot*p*2.0; a*=0.5;\n" +
            "  }\n" +
            "  return v;\n" +
            "}\n" +
            // ── Filament brightness from precomputed noise values ──────────────
            // n values passed in — computed ONCE in main, reused here and in color
            "float filamentVal(float n1,float n2,float n3,float n4,float n5){\n" +
            "  float f=exp(-abs(n1)*16.0)*0.55\n" +
            "         +exp(-abs(n2)*20.0)*0.40\n" +
            "         +exp(-abs(n3)*26.0)*0.25\n" +
            "         +exp(-abs(n4)*14.0)*0.35\n" +
            "         +exp(-abs(n5)*24.0)*0.20;\n" +
            "  return clamp(f,0.0,1.0);\n" +
            "}\n" +
            // ── Per-filament color from same precomputed values ────────────────
            "vec3 filamentCol(float n1,float n2,float n4){\n" +
            "  float w1=exp(-abs(n1)*16.0);\n" +
            "  float w2=exp(-abs(n2)*20.0);\n" +
            "  float w4=exp(-abs(n4)*14.0);\n" +
            "  float wt=max(w1+w2+w4,0.001);\n" +
            "  return vec3(0.55,0.05,0.90)*w1/wt\n" +
            "        +vec3(0.10,0.15,0.90)*w2/wt\n" +
            "        +vec3(0.80,0.05,0.55)*w4/wt;\n" +
            "}\n" +

            // ── Stars ─────────────────────────────────────────────────────────
            "vec3 starCol(float h){\n" +
            "  if(h<0.2) return vec3(0.55,0.78,1.00);\n" +
            "  if(h<0.4) return vec3(0.45,1.00,1.00);\n" +
            "  if(h<0.6) return vec3(1.00,1.00,1.00);\n" +
            "  if(h<0.8) return vec3(1.00,0.62,0.88);\n" +
            "  return      vec3(0.55,1.00,0.82);\n" +
            "}\n" +
            "vec3 starLayer(vec2 uv,float den,float ox,float oy){\n" +
            "  vec2 gp=uv*den+vec2(ox,oy);\n" +
            "  vec2 cell=floor(gp),f=fract(gp);\n" +
            "  float h=h1(cell);\n" +
            "  if(h>0.82){\n" +
            "    float d=length(f-h2(cell+3.7));\n" +
            "    float tw=0.60+0.40*sin(uTime*0.9+h*47.3);\n" +
            "    float core=exp(-d*d*2500.0)*tw;\n" +
            "    float halo=exp(-d*d*100.0)*tw*0.15;\n" +
            "    return starCol(h1(cell+9.1))*(core+halo);\n" +
            "  }\n" +
            "  return vec3(0.0);\n" +
            "}\n" +

            "void main(){\n" +
            "  float aspect=uRes.x/uRes.y;\n" +
            "  vec2  uv=vUv;\n" +
            "  vec2  p0=(uv-0.5)*vec2(aspect,1.0);\n" +

            // slowT drives writhing
            "  float slowT=uTime*0.028;\n" +

            // ── SCALE-SPACE FRACTAL ZOOM — guaranteed no jump ─────────────────
            // Rotation applied AFTER scale, same matrix for pA and pB.
            // pB=pA*2 in pre-rotation space → pB(t=1)=pA_new(t=0) exactly.
            // Always zooms toward screen center so filament structure always
            // elaborates on whatever is currently centred on screen.
            "  float zSpd=0.030;\n" +
            "  float t=fract(uTime*zSpd);\n" +
            "  float S=0.50;\n" +
            "  float zoom=exp(t*0.693);\n" +
            "  vec2 pAs=p0/zoom*S;\n" +
            "  vec2 pBs=pAs*2.0;\n" +
            "  float ang1=slowT*0.12;\n" +
            "  float ang2=slowT*0.05;\n" +
            "  float ca1=cos(ang1),sa1=sin(ang1);\n" +
            "  float ca2=cos(ang2),sa2=sin(ang2);\n" +
            "  vec2 rA=vec2(ca1*pAs.x-sa1*pAs.y,sa1*pAs.x+ca1*pAs.y);\n" +
            "  vec2 pA=vec2(ca2*rA.x-sa2*rA.y,sa2*rA.x+ca2*rA.y);\n" +
            "  vec2 rB=vec2(ca1*pBs.x-sa1*pBs.y,sa1*pBs.x+ca1*pBs.y);\n" +
            "  vec2 pB=vec2(ca2*rB.x-sa2*rB.y,sa2*rB.x+ca2*rB.y);\n" +
            "  float blend=t;\n" +

            // Compute noise for BOTH octaves, crossfade
            "  float n1a=sfbm(pA),       n1b=sfbm(pB);\n" +
            "  float n2a=sfbm(pA*1.5+vec2(3.2,1.8)), n2b=sfbm(pB*1.5+vec2(3.2,1.8));\n" +
            "  float n3a=sfbm(pA*2.3+vec2(7.1,4.3)), n3b=sfbm(pB*2.3+vec2(7.1,4.3));\n" +
            "  float n4a=sfbm(pA*0.7+vec2(1.4,6.2)), n4b=sfbm(pB*0.7+vec2(1.4,6.2));\n" +
            "  float n5a=sfbm(pA*3.1+vec2(5.5,2.7)), n5b=sfbm(pB*3.1+vec2(5.5,2.7));\n" +
            "  float n1=mix(n1a,n1b,blend);\n" +
            "  float n2=mix(n2a,n2b,blend);\n" +
            "  float n3=mix(n3a,n3b,blend);\n" +
            "  float n4=mix(n4a,n4b,blend);\n" +
            "  float n5=mix(n5a,n5b,blend);\n" +
            "  vec2 p=mix(pA,pB,blend);\n" +  // for hueNoise sample
            "  float raw=filamentVal(n1,n2,n3,n4,n5);\n" +
            "  float d=pow(raw,1.4);\n" +
            "  vec3 fCol=filamentCol(n1,n2,n4);\n" +

            // ── Color: use fCol to tint, mapped through brightness ────────────
            "  vec3 col=fCol*d*d*0.5 + vec3(0.15,0.01,0.30)*d*0.6;\n" +

            // ── Local color tint ──────────────────────────────────────────────
            "  float hueNoise=vn(p*3.0+vec2(uTime*0.006,uTime*0.004));\n" +
            "  col+=vec3(0.04,0.00,0.08)*hueNoise*d*0.3;\n" +

            // ── FEATURE 2: Very faint background wash ─────────────────────────
            "  float bgN=vn(p0*0.12+vec2(uTime*0.001,uTime*0.0008));\n" +
            "  col+=vec3(0.001,0.001,0.003)*bgN;\n" +

            // ── FEATURE 3: Hot spots in screen space ──────────────────────────
            "  vec2 hs1=vec2(sin(slowT*0.11+1.7)*0.55,cos(slowT*0.09+0.8)*0.35);\n" +
            "  vec2 hs2=vec2(sin(slowT*0.13+3.2)*0.60,cos(slowT*0.07+2.1)*0.38);\n" +
            "  vec2 hs3=vec2(sin(slowT*0.08+5.1)*0.45,cos(slowT*0.12+4.3)*0.40);\n" +
            "  float hd1=length(p0-hs1);\n" +
            "  float hd2=length(p0-hs2);\n" +
            "  float hd3=length(p0-hs3);\n" +
            "  float hotSum=exp(-hd1*hd1*22.0)+exp(-hd2*hd2*22.0)+exp(-hd3*hd3*22.0);\n" +
            "  col+=vec3(0.16,0.04,0.28)*hotSum*0.25;\n" +

            // ── FEATURE 5: Self-illumination on dense filaments only ───────────
            "  col+=vec3(0.04,0.01,0.10)*d*d*0.7;\n" +

            // ── Stars ─────────────────────────────────────────────────────────
            "  float SZSP=0.00576;\n" +
            "  float SZMAX=0.9163;\n" +
            "  float ph=uTime*SZSP;\n" +
            "  float t1=fract(ph+0.000);\n" +
            "  float t2=fract(ph+0.333);\n" +
            "  float t3=fract(ph+0.667);\n" +
            "  float f1=smoothstep(0.00,0.40,t1)*(1.0-smoothstep(0.60,1.00,t1));\n" +
            "  float f2=smoothstep(0.00,0.40,t2)*(1.0-smoothstep(0.60,1.00,t2));\n" +
            "  float f3=smoothstep(0.00,0.40,t3)*(1.0-smoothstep(0.60,1.00,t3));\n" +
            "  vec2 s1=(uv-0.5)/exp(t1*SZMAX)+0.5;\n" +
            "  vec2 s2=(uv-0.5)/exp(t2*SZMAX)+0.5;\n" +
            "  vec2 s3=(uv-0.5)/exp(t3*SZMAX)+0.5;\n" +
            "  col+=starLayer(s1,80.0,0.00,0.00)*f1;\n" +
            "  col+=starLayer(s2,80.0,0.37,0.21)*f2*0.85;\n" +
            "  col+=starLayer(s3,80.0,0.71,0.53)*f3*0.70;\n" +

            // ── Tonemap ───────────────────────────────────────────────────────
            "  col=col/(col+vec3(0.65));\n" +
            "  col=pow(max(col,vec3(0.0)),vec3(0.92));\n" +
            "  col*=1.12;\n" +

            // ── Fade in ───────────────────────────────────────────────────────
            "  col*=smoothstep(0.0,10.0,uTime);\n" +

            "  gl_FragColor=vec4(clamp(col,0.0,1.0),1.0);\n" +
            "}\n";

        private int prog, aPos, uTime, uRes;
        private FloatBuffer quadBuf;
        private long startMs;
        private int screenW, screenH;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
            GLES20.glClearColor(0f,0f,0f,1f);
            prog  = buildProg(VERT, FRAG);
            aPos  = GLES20.glGetAttribLocation(prog,"aPos");
            uTime = GLES20.glGetUniformLocation(prog,"uTime");
            uRes  = GLES20.glGetUniformLocation(prog,"uRes");
            ByteBuffer bb=ByteBuffer.allocateDirect(QUAD.length*4);
            bb.order(ByteOrder.nativeOrder());
            quadBuf=bb.asFloatBuffer();
            quadBuf.put(QUAD).position(0);
            startMs=System.currentTimeMillis();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            screenW=w; screenH=h;
            GLES20.glViewport(0,0,w,h);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            float t=(System.currentTimeMillis()-startMs)/1000f;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(prog);
            GLES20.glUniform1f(uTime,t);
            GLES20.glUniform2f(uRes,(float)screenW,(float)screenH);
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,8,quadBuf);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);
        }

        private int buildProg(String vs,String fs){
            int v=shader(GLES20.GL_VERTEX_SHADER,vs);
            int f=shader(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram();
            GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);
            GLES20.glLinkProgram(p);return p;
        }
        private int shader(int type,String src){
            int s=GLES20.glCreateShader(type);
            GLES20.glShaderSource(s,src);
            GLES20.glCompileShader(s);return s;
        }
    }
}
