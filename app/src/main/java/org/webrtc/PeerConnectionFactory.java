/*
 *  Copyright 2013 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import android.content.Context;
import java.util.List;

/**
 * Java wrapper for a C++ PeerConnectionFactoryInterface.  Main entry point to
 * the PeerConnection API for clients.
 */
public class PeerConnectionFactory {
  private static volatile boolean nativeLibLoaded;

  static {
    try {
      System.loadLibrary("mega");
      nativeLibLoaded = true;
    } catch (UnsatisfiedLinkError t) {
      nativeLibLoaded = false;
    }
  }

  private static final String TAG = "PeerConnectionFactory";
  private static final String VIDEO_CAPTURER_THREAD_NAME = "VideoCapturerThread";
  private final long nativeFactory;
  private static Context applicationContext;
  private static Thread networkThread;
  private static Thread workerThread;
  private static Thread signalingThread;
  private EglBase localEglbase;
  private EglBase remoteEglbase;

  public static class Options {
    // Keep in sync with webrtc/rtc_base/network.h!
    static final int ADAPTER_TYPE_UNKNOWN = 0;
    static final int ADAPTER_TYPE_ETHERNET = 1 << 0;
    static final int ADAPTER_TYPE_WIFI = 1 << 1;
    static final int ADAPTER_TYPE_CELLULAR = 1 << 2;
    static final int ADAPTER_TYPE_VPN = 1 << 3;
    static final int ADAPTER_TYPE_LOOPBACK = 1 << 4;

    public int networkIgnoreMask;
    public boolean disableEncryption;
    public boolean disableNetworkMonitor;
  }

  // Must be called at least once before creating a PeerConnectionFactory
  // (for example, at application startup time).
  public static native void nativeInitializeAndroidGlobals(
      Context context, boolean videoHwAcceleration);

  public static void initializeAndroidGlobals(Context context, boolean videoHwAcceleration) {
    ContextUtils.initialize(context);
    nativeInitializeAndroidGlobals(context, videoHwAcceleration);
  }

  // Older signature of initializeAndroidGlobals. The extra parameters are now meaningless.
  @Deprecated
  public static boolean initializeAndroidGlobals(Object context, boolean initializeAudio,
      boolean initializeVideo, boolean videoHwAcceleration) {
    initializeAndroidGlobals((Context) context, videoHwAcceleration);
    return true;
  }

  // Field trial initialization. Must be called before PeerConnectionFactory
  // is created.
  public static native void initializeFieldTrials(String fieldTrialsInitString);
  // Wrapper of webrtc::field_trial::FindFullName. Develop the feature with default behaviour off.
  // Example usage:
  // if (PeerConnectionFactory.fieldTrialsFindFullName("WebRTCExperiment").equals("Enabled")) {
  //   method1();
  // } else {
  //   method2();
  // }
  public static String fieldTrialsFindFullName(String name) {
    return nativeLibLoaded ? nativeFieldTrialsFindFullName(name) : "";
  }
  private static native String nativeFieldTrialsFindFullName(String name);
  // Internal tracing initialization. Must be called before PeerConnectionFactory is created to
  // prevent racing with tracing code.
  public static native void initializeInternalTracer();
  // Internal tracing shutdown, called to prevent resource leaks. Must be called after
  // PeerConnectionFactory is gone to prevent races with code performing tracing.
  public static native void shutdownInternalTracer();
  // Start/stop internal capturing of internal tracing.
  public static native boolean startInternalTracingCapture(String tracing_filename);
  public static native void stopInternalTracingCapture();

  @Deprecated
  public PeerConnectionFactory() {
    this(null);
  }

  // Note: initializeAndroidGlobals must be called at least once before
  // constructing a PeerConnectionFactory.
  public PeerConnectionFactory(Options options) {
    this(options, null /* encoderFactory */, null /* decoderFactory */);
  }

  public PeerConnectionFactory(
      Options options, VideoEncoderFactory encoderFactory, VideoDecoderFactory decoderFactory) {
    nativeFactory = nativeCreatePeerConnectionFactory(options, encoderFactory, decoderFactory);
    if (nativeFactory == 0) {
      throw new RuntimeException("Failed to initialize PeerConnectionFactory!");
    }
  }

  public PeerConnection createPeerConnection(PeerConnection.RTCConfiguration rtcConfig,
      MediaConstraints constraints, PeerConnection.Observer observer) {
    long nativeObserver = nativeCreateObserver(observer);
    if (nativeObserver == 0) {
      return null;
    }
    long nativePeerConnection =
        nativeCreatePeerConnection(nativeFactory, rtcConfig, constraints, nativeObserver);
    if (nativePeerConnection == 0) {
      return null;
    }
    return new PeerConnection(nativePeerConnection, nativeObserver);
  }

  public PeerConnection createPeerConnection(List<PeerConnection.IceServer> iceServers,
      MediaConstraints constraints, PeerConnection.Observer observer) {
    PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
    return createPeerConnection(rtcConfig, constraints, observer);
  }

  public MediaStream createLocalMediaStream(String label) {
    return new MediaStream(nativeCreateLocalMediaStream(nativeFactory, label));
  }

  public VideoSource createVideoSource(VideoCapturer capturer) {
    final EglBase.Context eglContext =
        localEglbase == null ? null : localEglbase.getEglBaseContext();
    final SurfaceTextureHelper surfaceTextureHelper =
        SurfaceTextureHelper.create(VIDEO_CAPTURER_THREAD_NAME, eglContext);
    long nativeAndroidVideoTrackSource =
        nativeCreateVideoSource(nativeFactory, surfaceTextureHelper, capturer.isScreencast());
    VideoCapturer.CapturerObserver capturerObserver =
        new AndroidVideoTrackSourceObserver(nativeAndroidVideoTrackSource);
    capturer.initialize(
        surfaceTextureHelper, ContextUtils.getApplicationContext(), capturerObserver);
    return new VideoSource(nativeAndroidVideoTrackSource);
  }

  public VideoTrack createVideoTrack(String id, VideoSource source) {
    return new VideoTrack(nativeCreateVideoTrack(nativeFactory, id, source.nativeSource));
  }

  public AudioSource createAudioSource(MediaConstraints constraints) {
    return new AudioSource(nativeCreateAudioSource(nativeFactory, constraints));
  }

  public AudioTrack createAudioTrack(String id, AudioSource source) {
    return new AudioTrack(nativeCreateAudioTrack(nativeFactory, id, source.nativeSource));
  }

  // Starts recording an AEC dump. Ownership of the file is transfered to the
  // native code. If an AEC dump is already in progress, it will be stopped and
  // a new one will start using the provided file.
  public boolean startAecDump(int file_descriptor, int filesize_limit_bytes) {
    return nativeStartAecDump(nativeFactory, file_descriptor, filesize_limit_bytes);
  }

  // Stops recording an AEC dump. If no AEC dump is currently being recorded,
  // this call will have no effect.
  public void stopAecDump() {
    nativeStopAecDump(nativeFactory);
  }

  @Deprecated
  public void setOptions(Options options) {
    nativeSetOptions(nativeFactory, options);
  }

  /** Set the EGL context used by HW Video encoding and decoding.
   *
   * @param localEglContext   Must be the same as used by VideoCapturerAndroid and any local video
   *                          renderer.
   * @param remoteEglContext  Must be the same as used by any remote video renderer.
   */
  public void setVideoHwAccelerationOptions(
      EglBase.Context localEglContext, EglBase.Context remoteEglContext) {
    if (localEglbase != null) {
      Logging.w(TAG, "Egl context already set.");
      localEglbase.release();
    }
    if (remoteEglbase != null) {
      Logging.w(TAG, "Egl context already set.");
      remoteEglbase.release();
    }
    localEglbase = EglBase.create(localEglContext);
    remoteEglbase = EglBase.create(remoteEglContext);
    nativeSetVideoHwAccelerationOptions(
        nativeFactory, localEglbase.getEglBaseContext(), remoteEglbase.getEglBaseContext());
  }

  public void dispose() {
    nativeFreeFactory(nativeFactory);
    networkThread = null;
    workerThread = null;
    signalingThread = null;
    if (localEglbase != null)
      localEglbase.release();
    if (remoteEglbase != null)
      remoteEglbase.release();
  }

  public void threadsCallbacks() {
    nativeThreadsCallbacks(nativeFactory);
  }

  private static void printStackTrace(Thread thread, String threadName) {
    if (thread != null) {
      StackTraceElement[] stackTraces = thread.getStackTrace();
      if (stackTraces.length > 0) {
        Logging.d(TAG, threadName + " stacks trace:");
        for (StackTraceElement stackTrace : stackTraces) {
          Logging.d(TAG, stackTrace.toString());
        }
      }
    }
  }

  public static void printStackTraces() {
    printStackTrace(networkThread, "Network thread");
    printStackTrace(workerThread, "Worker thread");
    printStackTrace(signalingThread, "Signaling thread");
  }

  private static void onNetworkThreadReady() {
    networkThread = Thread.currentThread();
    Logging.d(TAG, "onNetworkThreadReady");
  }

  private static void onWorkerThreadReady() {
    workerThread = Thread.currentThread();
    Logging.d(TAG, "onWorkerThreadReady");
  }

  private static void onSignalingThreadReady() {
    signalingThread = Thread.currentThread();
    Logging.d(TAG, "onSignalingThreadReady");
  }

  private static native long nativeCreatePeerConnectionFactory(
      Options options, VideoEncoderFactory encoderFactory, VideoDecoderFactory decoderFactory);

  private static native long nativeCreateObserver(PeerConnection.Observer observer);

  private static native long nativeCreatePeerConnection(long nativeFactory,
      PeerConnection.RTCConfiguration rtcConfig, MediaConstraints constraints, long nativeObserver);

  private static native long nativeCreateLocalMediaStream(long nativeFactory, String label);

  private static native long nativeCreateVideoSource(
      long nativeFactory, SurfaceTextureHelper surfaceTextureHelper, boolean is_screencast);

  private static native long nativeCreateVideoTrack(
      long nativeFactory, String id, long nativeVideoSource);

  private static native long nativeCreateAudioSource(
      long nativeFactory, MediaConstraints constraints);

  private static native long nativeCreateAudioTrack(
      long nativeFactory, String id, long nativeSource);

  private static native boolean nativeStartAecDump(
      long nativeFactory, int file_descriptor, int filesize_limit_bytes);

  private static native void nativeStopAecDump(long nativeFactory);

  @Deprecated public native void nativeSetOptions(long nativeFactory, Options options);

  private static native void nativeSetVideoHwAccelerationOptions(
      long nativeFactory, Object localEGLContext, Object remoteEGLContext);

  private static native void nativeThreadsCallbacks(long nativeFactory);

  private static native void nativeFreeFactory(long nativeFactory);
}
