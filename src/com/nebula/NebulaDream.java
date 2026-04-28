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

            // ── Ridged FBM ────────────────────────────────────────────────────
            "float ridgedFBM(vec2 p){\n" +
            "  float v=0.0,a=0.54;\n" +
            "  mat2 rot=mat2(0.8,-0.6,0.6,0.8);\n" +
            "  for(int i=0;i<4;i++){\n" +
            "    float n=vn(p)*2.0-1.0;\n" +
            "    v+=a*(1.0-abs(n));\n" +
            "    p=rot*p*2.1; a*=0.48;\n" +
            "  }\n" +
            "  return v;\n" +
            "}\n" +
            "float cloud(vec2 p){\n" +
            "  vec2 q=vec2(ridgedFBM(p),ridgedFBM(p+vec2(3.7,1.8)));\n" +
            "  return ridgedFBM(p+1.0*(q-0.5));\n" +
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
            "  if(h>0.82){\n" +         // lower threshold → more stars
            "    float d=length(f-h2(cell+3.7));\n" +
            "    float tw=0.60+0.40*sin(uTime*0.9+h*47.3);\n" +
            "    float core=exp(-d*d*2500.0)*tw;\n" +   // sharper point
            "    float halo=exp(-d*d*100.0)*tw*0.15;\n" + // tiny halo only
            "    return starCol(h1(cell+9.1))*(core+halo);\n" +
            "  }\n" +
            "  return vec3(0.0);\n" +
            "}\n" +

            "void main(){\n" +
            "  float aspect=uRes.x/uRes.y;\n" +
            "  vec2  uv=vUv;\n" +
            "  vec2  p0=(uv-0.5)*vec2(aspect,1.0);\n" +

            // ── Nebula: scale-space zoom + tiny drift ─────────────────────────
            // Drift amplitude is now 0.18 — well below screen coord range (~0.8)
            // so zoom-in effect dominates, drift just prevents exact repetition
            "  float SPEED=0.038;\n" +
            "  float LN2=0.6931472;\n" +
            "  float logZ=uTime*SPEED;\n" +
            "  float t=fract(logZ);\n" +
            "  float zoom=exp(t*LN2);\n" +
            "  float S=1.4;\n" +
            "  vec2 drift=vec2(\n" +
            "    sin(logZ*0.31)*0.12+sin(logZ*0.17)*0.06,\n" +
            "    cos(logZ*0.23)*0.10+cos(logZ*0.13)*0.05\n" +
            "  );\n" +
            "  vec2 pA=p0/zoom*S+drift;\n" +
            "  vec2 pB=p0/zoom*S*2.0+drift;\n" +
            "  float edgeW=exp(-dot(p0,p0)*0.5);\n" +
            "  float tAdj=t*(0.5+0.5*edgeW);\n" +
            "  float blend=smoothstep(0.22,0.78,tAdj);\n" +
            "  float raw=mix(cloud(pA),cloud(pB),blend);\n" +

            // ── Density ───────────────────────────────────────────────────────
            "  float dens=max(0.0,raw-0.38);\n" +
            "  float d=pow(clamp(dens*2.2,0.0,1.0),3.5);\n" +

            // ── Color ─────────────────────────────────────────────────────────
            "  vec3 c0=vec3(0.000,0.000,0.000);\n" +
            "  vec3 c1=vec3(0.005,0.000,0.018);\n" +
            "  vec3 c2=vec3(0.030,0.002,0.080);\n" +
            "  vec3 c3=vec3(0.095,0.008,0.200);\n" +
            "  vec3 c4=vec3(0.180,0.030,0.300);\n" +
            "  vec3 col;\n" +
            "  if(d<0.25)      col=mix(c0,c1,d*4.0);\n" +
            "  else if(d<0.50) col=mix(c1,c2,(d-0.25)*4.0);\n" +
            "  else if(d<0.75) col=mix(c2,c3,(d-0.50)*4.0);\n" +
            "  else            col=mix(c3,c4,(d-0.75)*4.0);\n" +

            // ── Stars: slow subtle radial expansion, 3 phases, very slow fade ──
            // SZSP=0.004 → one full cycle every 250s (very slow drift outward)
            // SZMAX=ln(2.5) → max 2.5x zoom — stars never get big enough to blob
            // Each layer fades in over first 40% and out over last 40%
            // Only 20% of cycle at full brightness — always crossfading
            "  float SZSP=0.004;\n" +             // 250s per cycle
            "  float SZMAX=0.9163;\n" +            // ln(2.5)
            "  float ph=uTime*SZSP;\n" +
            "  float t1=fract(ph+0.000);\n" +
            "  float t2=fract(ph+0.333);\n" +
            "  float t3=fract(ph+0.667);\n" +
            "  float z1=exp(t1*SZMAX);\n" +
            "  float z2=exp(t2*SZMAX);\n" +
            "  float z3=exp(t3*SZMAX);\n" +
            // Fade in 0→0.4, full 0.4→0.6, fade out 0.6→1.0
            "  float f1=smoothstep(0.00,0.40,t1)*(1.0-smoothstep(0.60,1.00,t1));\n" +
            "  float f2=smoothstep(0.00,0.40,t2)*(1.0-smoothstep(0.60,1.00,t2));\n" +
            "  float f3=smoothstep(0.00,0.40,t3)*(1.0-smoothstep(0.60,1.00,t3));\n" +
            "  vec2 s1=(uv-0.5)/z1+0.5;\n" +
            "  vec2 s2=(uv-0.5)/z2+0.5;\n" +
            "  vec2 s3=(uv-0.5)/z3+0.5;\n" +
            // Higher density (80) → more numerous, smaller cells → tiny sharp points
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
