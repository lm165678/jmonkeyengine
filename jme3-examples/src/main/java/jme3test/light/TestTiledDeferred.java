/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.*;
import com.jme3.light.*;
import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.deferred.TiledDeferredProcessor;
import com.jme3.renderer.deferred.TiledDeferredProcessor.Mode;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Press 1/2/3/4 for forward/CPU/GPU/auto. Run with --smoke for offscreen checks. */
public class TestTiledDeferred extends SimpleApplication {
 private final boolean smoke;
 private static boolean cpuOnly;
 private static boolean benchmark;
 private ByteBuffer benchmarkReadback;
 private long lastBenchmarkFrame;
 private final double[] benchmarkTimes=new double[120];
 private static int lightTotal=64;
 private TiledDeferredProcessor pipeline;
 private int frame;
 private byte[] cpu,forward;
 public TestTiledDeferred(boolean smoke){this.smoke=smoke;}
 public static void main(String[] args){
  benchmark=Arrays.asList(args).contains("--benchmark");
  cpuOnly=Arrays.asList(args).contains("--gl33");
  if(Arrays.asList(args).contains("--overflow"))lightTotal=192;
  TestTiledDeferred app=new TestTiledDeferred((benchmark || Arrays.asList(args).contains("--smoke")));
  AppSettings s=new AppSettings(true);s.setResolution(640,480);s.setRenderer(cpuOnly?AppSettings.LWJGL_OPENGL33:AppSettings.LWJGL_OPENGL43);s.setSamples(0);s.setAudioRenderer(null);s.setVSync(false);
  app.setSettings(s);app.setShowSettings(false);
  app.start(app.smoke?JmeContext.Type.OffscreenSurface:JmeContext.Type.Display);
 }
 @Override public void simpleInitApp(){
  cam.setLocation(new Vector3f(0,4,12));cam.lookAt(Vector3f.ZERO,Vector3f.UNIT_Y);
  flyCam.setMoveSpeed(10);viewPort.setBackgroundColor(new ColorRGBA(.03f,.03f,.03f,1));
  for(int y=-2;y<=2;y++)for(int x=-4;x<=4;x++){
   Geometry g=new Geometry("box",new Box(.35f,.35f,.35f));g.setLocalTranslation(x,y,0);
   Material m=new Material(assetManager,"Common/MatDefs/Light/Lighting.j3md");m.setBoolean("UseMaterialColors",true);m.setColor("Diffuse",new ColorRGBA(.5f,.6f,.8f,1));m.setColor("Ambient",ColorRGBA.White);m.setColor("Specular",ColorRGBA.White);m.setFloat("Shininess",32);if(x>0){m=new Material(assetManager,"Common/MatDefs/Light/PBRLighting.j3md");m.setColor("BaseColor",new ColorRGBA(.5f,.6f,.8f,1));m.setFloat("Metallic",.3f);m.setFloat("Roughness",.5f);}
   g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);g.setMaterial(m);rootNode.attachChild(g);
  }
  AmbientLight a=new AmbientLight();a.setColor(new ColorRGBA(.05f,.05f,.05f,1));rootNode.addLight(a);
  for(int i=0;i<lightTotal;i++){
   PointLight p=new PointLight();p.setPosition(new Vector3f((i%8-3.5f)*1.2f,(i/8-3.5f)*.8f,2));p.setRadius(lightTotal>128?100:3);
   p.setColor(new ColorRGBA((i%3==0)?.3f:.05f,(i%3==1)?.3f:.05f,(i%3==2)?.3f:.05f,1));if(lightTotal>128)p.setColor(p.getColor().mult(.02f));rootNode.addLight(p);
  }
  Geometry fallback=new Geometry("forward unshaded",new Box(.4f,.4f,.4f));fallback.setLocalTranslation(0,0,1);
  Material unlit=new Material(assetManager,"Common/MatDefs/Misc/Unshaded.j3md");unlit.setColor("Color",ColorRGBA.Yellow);fallback.setMaterial(unlit);rootNode.attachChild(fallback);
  DirectionalLight sun=new DirectionalLight();sun.setDirection(new Vector3f(-1,-2,-1).normalizeLocal());sun.setColor(new ColorRGBA(.2f,.2f,.2f,1));rootNode.addLight(sun);
  SpotLight spot=new SpotLight();spot.setPosition(new Vector3f(0,4,5));spot.setDirection(new Vector3f(0,-4,-5).normalizeLocal());spot.setSpotRange(20);spot.setColor(new ColorRGBA(.2f,.1f,.1f,1));rootNode.addLight(spot);
  Geometry glass=new Geometry("transparent forward",new Box(.6f,.6f,.1f));glass.setLocalTranslation(-2,0,2);
  Material gm=new Material(assetManager,"Common/MatDefs/Misc/Unshaded.j3md");gm.setColor("Color",new ColorRGBA(0,1,1,.4f));gm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);glass.setMaterial(gm);glass.setQueueBucket(RenderQueue.Bucket.Transparent);rootNode.attachChild(glass);
  com.jme3.shadow.DirectionalLightShadowRenderer shadows=new com.jme3.shadow.DirectionalLightShadowRenderer(assetManager,512,2);shadows.setLight(sun);viewPort.addProcessor(shadows);
  com.jme3.post.FilterPostProcessor fpp=new com.jme3.post.FilterPostProcessor(assetManager);fpp.addFilter(new com.jme3.post.filters.FXAAFilter());viewPort.addProcessor(fpp);
  pipeline=new TiledDeferredProcessor(assetManager);
  if (lightTotal > 128) {
   // Exercise overflow even with adaptive capacity: emulate a small SSBO limit.
   // At 640x480 with 16-pixel tiles, 256 entries exceed 1 MiB; 128 fit.
   renderer.getLimits().put(com.jme3.renderer.Limits.ShaderStorageBufferObjectMaxBlockSize, 1024 * 1024);
   pipeline.setTileSize(16);
  }
  pipeline.registerShadingModel(7,(String)assetManager.loadAsset("Materials/Tiled/CustomLambert.frag"));
  Geometry custom=new Geometry("ShadingId 7",new Box(.3f,.3f,.3f));custom.setLocalTranslation(-1.5f,1.5f,1);
  custom.setMaterial(new Material(assetManager,"Materials/Tiled/CustomLambert.j3md"));rootNode.attachChild(custom);
  viewPort.addProcessor(pipeline);
  if(!smoke){
   String[] names={"Forward","CPU","GPU","Auto"};int[] keys={KeyInput.KEY_1,KeyInput.KEY_2,KeyInput.KEY_3,KeyInput.KEY_4};
   for(int i=0;i<4;i++)inputManager.addMapping(names[i],new KeyTrigger(keys[i]));
   inputManager.addListener((ActionListener)(name,pressed,tpf)->{if(pressed){pipeline.setMode(Mode.values()[Arrays.asList(names).indexOf(name)]);System.out.println("Requested mode: "+pipeline.getMode());}},names);
  }else{
   setDisplayFps(false);setDisplayStatView(false);
   viewPort.addProcessor(new SceneProcessor(){
    public void initialize(RenderManager rm,ViewPort vp){} public boolean isInitialized(){return true;}public void reshape(ViewPort vp,int w,int h){}public void preFrame(float t){}public void postQueue(RenderQueue q){}public void cleanup(){}public void setProfiler(AppProfiler p){}
    public void postFrame(FrameBuffer out){check(out);}
   });
  }
 }
 private void check(FrameBuffer out){
  if(benchmark){benchmarkFrame();return;}
  frame++;
  if(frame==3){forward=capture(out);save(forward,"forward");pipeline.setMode(Mode.TiledDeferredCPU);}
  if(frame==6){
   if(pipeline.getDeferredGeometryCount()!=47)throw new AssertionError("Deferred geometry count: "+pipeline.getDeferredGeometryCount()+" / "+pipeline.getFallbackReason());
   cpu=capture(out);save(cpu,"cpu");report(forward,cpu,"Forward/CPU");pipeline.setMode(Mode.TiledDeferredGPU);
  }
  if(frame==9){
   if(pipeline.getActiveMode()!=(cpuOnly?Mode.TiledDeferredCPU:Mode.TiledDeferredGPU))throw new AssertionError("GPU unavailable: "+pipeline.getFallbackReason());
   compare(cpu,capture(out),3,"CPU/GPU");pipeline.setMode(Mode.Forward);
  }
  if(frame==12){compare(forward,capture(out),0,"Forward round trip");pipeline.setMode(Mode.Auto);}
  if(frame==15){compare(cpu,capture(out),3,"Auto after reallocation");cam.resize(321,239,true);renderManager.notifyReshape(321,239);pipeline.setMode(Mode.TiledDeferredCPU);}
  if(frame==18){cpu=capture(out);pipeline.setMode(Mode.TiledDeferredGPU);}
  if(frame==21){compare(cpu,capture(out),3,"CPU/GPU after odd-size resize");System.out.println("TILED_SMOKE_PASS backend parity, Forward round trip, Auto and resize");stop();}
 }
 private void benchmarkFrame(){
  if(benchmarkReadback==null)benchmarkReadback=BufferUtils.createByteBuffer(cam.getWidth()*cam.getHeight()*4);
  benchmarkReadback.clear();renderer.readFrameBuffer(null,benchmarkReadback);
  long now=System.nanoTime();int within=frame%150;
  if(within>=30)benchmarkTimes[within-30]=(now-lastBenchmarkFrame)/1e6;
  lastBenchmarkFrame=now;frame++;
  if(frame%150==0){
   Arrays.sort(benchmarkTimes);
   System.out.println("TILED_BENCHMARK mode="+pipeline.getActiveMode()+" medianFrameMs="+benchmarkTimes[60]+" p95FrameMs="+benchmarkTimes[114]+" deferred="+pipeline.getDeferredGeometryCount()+" forward="+pipeline.getForwardGeometryCount()+" (includes synchronous readback)");
   if(frame==150)pipeline.setMode(Mode.TiledDeferredCPU);
   else if(frame==300)pipeline.setMode(Mode.TiledDeferredGPU);
   else stop();
  }
 }
 private byte[] capture(FrameBuffer fb){ByteBuffer b=BufferUtils.createByteBuffer(cam.getWidth()*cam.getHeight()*4);renderer.readFrameBuffer(null,b);byte[] data=new byte[b.capacity()];b.clear();b.get(data);return data;}
 private void save(byte[] data,String name){
  java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(cam.getWidth(),cam.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
  for(int y=0;y<cam.getHeight();y++)for(int x=0;x<cam.getWidth();x++){int i=(y*cam.getWidth()+x)*4;image.setRGB(x,cam.getHeight()-1-y,((data[i]&255)<<16)|((data[i+1]&255)<<8)|(data[i+2]&255));}
  try{java.io.File dir=new java.io.File("build/tiled-smoke");dir.mkdirs();javax.imageio.ImageIO.write(image,"png",new java.io.File(dir,name+".png"));}catch(java.io.IOException e){throw new RuntimeException(e);}
 }
 private void report(byte[] a,byte[] b,String name){int max=0;long total=0;for(int i=0;i<a.length;i++){int d=Math.abs((a[i]&255)-(b[i]&255));max=Math.max(max,d);total+=d;}System.out.println(name+" max="+max+" mean="+(double)total/a.length);}
 private void compare(byte[] a,byte[] b,int tolerance,String name){int max=0;for(int i=0;i<a.length;i++)max=Math.max(max,Math.abs((a[i]&255)-(b[i]&255)));if(max>tolerance)throw new AssertionError(name+" pixel error "+max);System.out.println(name+" max pixel error="+max);}
 @Override public void handleError(String message,Throwable error){error.printStackTrace();if(smoke)System.exit(1);super.handleError(message,error);}
}


