import React, { useEffect, useRef, useState } from 'react';
import {
  Camera,
  HelpCircle,
  Image as ImageIcon,
  Play,
  Settings,
  Square,
  Sun,
  Trash2,
  Wifi,
  WifiOff,
  X,
} from 'lucide-react';
import {
  AlertChannel,
  EmulatorSettings,
  FailoverTraceEntry,
  MonitoringState,
  MotionTelemetryState,
  NetworkMode,
  PhoneTab,
  SimNetworkEnvironment,
  SnapshotItem,
} from '../types';
import {
  WebMotionDetector,
  WebSmsRateLimiter,
  simulateFailoverDispatch,
} from '../utils/motionEngine';

interface Props {
  settings: EmulatorSettings;
  onUpdateSettings: (patch: Partial<EmulatorSettings>) => void;
  networkEnv: SimNetworkEnvironment;
  onUpdateNetworkEnv: (patch: Partial<SimNetworkEnvironment>) => void;
  onAddTrace: (entry: FailoverTraceEntry) => void;
  onTelemetryUpdate: (telemetry: MotionTelemetryState) => void;
}

export const AndroidPhoneSimulator: React.FC<Props> = ({
  settings,
  onUpdateSettings,
  networkEnv,
  onUpdateNetworkEnv,
  onAddTrace,
  onTelemetryUpdate,
}) => {
  const [activeTab, setActiveTab] = useState<PhoneTab>('MONITOR');
  const [monitoringState, setMonitoringState] = useState<MonitoringState>('IDLE');
  const [exitCountdown, setExitCountdown] = useState<number>(0);
  const [stealthActive, setStealthActive] = useState<boolean>(false);

  // Camera source: synthetic hallway feed by default, or live webcam if user enables it
  const [useLiveWebcam, setUseLiveWebcam] = useState<boolean>(false);
  const [webcamError, setWebcamError] = useState<string | null>(null);
  const [injectIntruderFrames, setInjectIntruderFrames] = useState<number>(0);
  const [injectExposureFrames, setInjectExposureFrames] = useState<number>(0);
  const [flashBurstActive, setFlashBurstActive] = useState<boolean>(false);
  const [liveBox, setLiveBox] = useState<import('../types').MotionBoundingBox | null>(null);

  // Gallery & Viewer
  const [snapshots, setSnapshots] = useState<SnapshotItem[]>([]);
  const [viewingSnapshot, setViewingSnapshot] = useState<SnapshotItem | null>(null);

  // Settings helpers
  const [showBotFatherModal, setShowBotFatherModal] = useState<boolean>(false);
  const [diagnosticLogs, setDiagnosticLogs] = useState<
    Array<{ id: string; tier: string; ok: boolean; msg: string }>
  >([]);
  const [diagnosticBusy, setDiagnosticBusy] = useState<boolean>(false);
  const [bannerToast, setBannerToast] = useState<string | null>(null);

  // Stealth long-press timer
  const longPressTimerRef = useRef<number | null>(null);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const analysisCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const detectorRef = useRef<WebMotionDetector>(new WebMotionDetector());
  const smsLimiterRef = useRef<WebSmsRateLimiter>(new WebSmsRateLimiter());

  const settingsRef = useRef(settings);
  settingsRef.current = settings;
  const networkEnvRef = useRef(networkEnv);
  networkEnvRef.current = networkEnv;
  const monitoringStateRef = useRef(monitoringState);
  monitoringStateRef.current = monitoringState;
  const activeTabRef = useRef(activeTab);
  activeTabRef.current = activeTab;
  const stealthActiveRef = useRef(stealthActive);
  stealthActiveRef.current = stealthActive;

  // Manage live webcam stream if toggled
  useEffect(() => {
    let stream: MediaStream | null = null;
    let cancelled = false;

    async function startCam() {
      if (!useLiveWebcam) {
        setWebcamError(null);
        return;
      }
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment', width: { ideal: 640 }, height: { ideal: 480 } },
          audio: false,
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          await videoRef.current.play();
        }
        setWebcamError(null);
      } catch (err) {
        setWebcamError((err as Error).message || 'Camera access denied; using synthetic feed.');
        setUseLiveWebcam(false);
      }
    }

    startCam();
    return () => {
      cancelled = true;
      if (stream) {
        stream.getTracks().forEach((t) => t.stop());
      }
    };
  }, [useLiveWebcam]);

  // Exit delay countdown effect
  useEffect(() => {
    if (monitoringState !== 'EXIT_DELAY') return;
    if (exitCountdown <= 0) {
      setMonitoringState('ARMED');
      detectorRef.current.reset();
      if (settingsRef.current.stealthAutoActivate) {
        setStealthActive(true);
      }
      return;
    }
    const timer = window.setTimeout(() => {
      setExitCountdown((c) => c - 1);
    }, 1000);
    return () => window.clearTimeout(timer);
  }, [monitoringState, exitCountdown]);

  // Toast auto-dismiss
  useEffect(() => {
    if (!bannerToast) return;
    const t = window.setTimeout(() => setBannerToast(null), 4000);
    return () => window.clearTimeout(t);
  }, [bannerToast]);

  // Continuous Canvas Render + ~3.5 FPS Luma Motion Engine Loop
  useEffect(() => {
    let rafId = 0;
    let frameTick = 0;
    let intruderTicks = 0;
    let exposureTicks = 0;

    const renderLoop = () => {
      frameTick++;
      const canvas = canvasRef.current;
      const aCanvas = analysisCanvasRef.current;
      if (canvas && aCanvas) {
        const ctx = canvas.getContext('2d');
        const aCtx = aCanvas.getContext('2d', { willReadFrequently: true });
        if (ctx && aCtx) {
          const w = canvas.width;
          const h = canvas.height;

          if (useLiveWebcam && videoRef.current && videoRef.current.readyState >= 2) {
            ctx.drawImage(videoRef.current, 0, 0, w, h);
          } else {
            // Draw realistic dark indoor hallway scene
            const grad = ctx.createLinearGradient(0, 0, 0, h);
            grad.addColorStop(0, '#101622');
            grad.addColorStop(0.65, '#192233');
            grad.addColorStop(1, '#0d121c');
            ctx.fillStyle = grad;
            ctx.fillRect(0, 0, w, h);

            // Perspective doorway & floor grid
            ctx.strokeStyle = 'rgba(148, 163, 184, 0.14)';
            ctx.lineWidth = 1;
            ctx.strokeRect(w * 0.34, h * 0.14, w * 0.32, h * 0.62);
            ctx.beginPath();
            ctx.moveTo(0, h);
            ctx.lineTo(w * 0.34, h * 0.76);
            ctx.moveTo(w, h);
            ctx.lineTo(w * 0.66, h * 0.76);
            ctx.stroke();

            // Subtle ambient wall sconce
            const glow = ctx.createRadialGradient(w * 0.22, h * 0.32, 2, w * 0.22, h * 0.32, 55);
            glow.addColorStop(0, 'rgba(56, 189, 248, 0.22)');
            glow.addColorStop(1, 'rgba(56, 189, 248, 0)');
            ctx.fillStyle = glow;
            ctx.fillRect(0, 0, w, h);

            // Synthetic Intruder silhouette if triggered
            if (injectIntruderFrames > 0 || intruderTicks > 0) {
              if (intruderTicks === 0) intruderTicks = 36;
              const progress = (36 - intruderTicks) / 36;
              const xPos = w * (0.28 + progress * 0.42);
              ctx.fillStyle = '#e2e8f0';
              ctx.beginPath();
              ctx.arc(xPos, h * 0.38, 13, 0, Math.PI * 2);
              ctx.fill();
              ctx.fillRect(xPos - 14, h * 0.46, 28, 64);
              intruderTicks--;
              if (intruderTicks === 0) setInjectIntruderFrames(0);
            }

            // Synthetic Global Exposure Jump (e.g. cloud passing / auto-exposure)
            if (injectExposureFrames > 0 || exposureTicks > 0) {
              if (exposureTicks === 0) exposureTicks = 24;
              ctx.fillStyle = 'rgba(255, 255, 255, 0.34)';
              ctx.fillRect(0, 0, w, h);
              exposureTicks--;
              if (exposureTicks === 0) setInjectExposureFrames(0);
            }

            // Timestamp watermark
            ctx.fillStyle = 'rgba(226, 232, 240, 0.65)';
            ctx.font = '11px "JetBrains Mono", monospace';
            ctx.fillText(
              `${settingsRef.current.cameraLabel} · ${new Date().toLocaleTimeString()}`,
              12,
              18
            );
          }

          // Run YUV/Y-plane luma motion detection at ~3.5 FPS during Setup calibration (previewOnly) and when ARMED
          const shouldAnalyzeFrame =
            monitoringStateRef.current === 'ARMED' ||
            (monitoringStateRef.current === 'IDLE' &&
              activeTabRef.current === 'MONITOR' &&
              !stealthActiveRef.current);

          if (shouldAnalyzeFrame && frameTick % 6 === 0) {
            const isPreviewOnly = monitoringStateRef.current !== 'ARMED';
            aCtx.drawImage(canvas, 0, 0, aCanvas.width, aCanvas.height);
            const frame = aCtx.getImageData(0, 0, aCanvas.width, aCanvas.height);
            const nowMs = Date.now();
            const res = detectorRef.current.analyzeRgbaFrame(
              frame.data,
              aCanvas.width,
              aCanvas.height,
              nowMs,
              settingsRef.current.sensitivityPercent,
              settingsRef.current.cooldownSeconds,
              settingsRef.current.requireRegionCrossing,
              settingsRef.current.zoneLayout,
              isPreviewOnly
            );

            if (res.status !== 'THROTTLED' && res.status !== 'BASELINE') {
              setLiveBox(res.boundingBox);
              onTelemetryUpdate({
                changedAreaPercent: res.changedAreaPercent,
                meanLumaShift: res.meanSignedDelta,
                globalExposureSuppressed: res.status === 'EXPOSURE_SUPPRESSED',
                consecutiveMotionFrames: res.consecutiveFrames,
                cooldownRemainingSeconds: isPreviewOnly ? 0 : res.cooldownRemainingSeconds,
                fps: 3.5,
                boundingBox: res.boundingBox,
              });
            }

            if (res.status === 'EXPOSURE_SUPPRESSED' && !isPreviewOnly) {
              onAddTrace({
                id: `${nowMs}-exp`,
                timestampMs: nowMs,
                event: 'Global Exposure Filtered',
                tier: 'LOCAL_ONLY',
                detail: `Suppressed whole-frame luma shift (${res.meanSignedDelta.toFixed(1)} delta). No false alarm.`,
              });
            }

            if (res.status === 'TRIGGERED') {
              if (settingsRef.current.deterrentFlashEnabled) {
                setFlashBurstActive(true);
                window.setTimeout(() => setFlashBurstActive(false), 180);
              }
              if (settingsRef.current.deterrentShutterSoundEnabled) {
                try {
                  const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
                  if (AudioCtx) {
                    const ac = new AudioCtx();
                    const osc = ac.createOscillator();
                    const gain = ac.createGain();
                    osc.type = 'triangle';
                    osc.frequency.setValueAtTime(1400, ac.currentTime);
                    osc.frequency.exponentialRampToValueAtTime(180, ac.currentTime + 0.055);
                    gain.gain.setValueAtTime(0.32, ac.currentTime);
                    gain.gain.exponentialRampToValueAtTime(0.001, ac.currentTime + 0.06);
                    osc.connect(gain);
                    gain.connect(ac.destination);
                    osc.start();
                    osc.stop(ac.currentTime + 0.065);
                    window.setTimeout(() => void ac.close(), 150);
                  }
                } catch {
                  // Ignore audio block if autoplay hasn't been unlocked yet
                }
              }
              detectorRef.current.reset();

              const snapshotDataUrl = canvas.toDataURL('image/jpeg', 0.82);
              const compressedKb = Math.round((snapshotDataUrl.length * 0.75) / 1024);
              const dateTag = new Date(nowMs).toISOString().replace(/[:.]/g, '-').slice(0, 19);
              const filename = `HomeAlert_${settingsRef.current.cameraLabel.replace(/\s+/g, '_')}_${dateTag}.jpg`;

              void (async () => {
                const dispatch = await simulateFailoverDispatch(
                  settingsRef.current,
                  networkEnvRef.current,
                  smsLimiterRef.current,
                  snapshotDataUrl,
                  res.changedAreaPercent,
                  nowMs
                );

                const entry: SnapshotItem = {
                  id: `${nowMs}-${Math.random().toString(36).slice(2, 6)}`,
                  filename,
                  timestampMs: nowMs,
                  cameraLabel: settingsRef.current.cameraLabel,
                  motionPercent: res.changedAreaPercent,
                  deliveryTier: dispatch.tier,
                  deliveryStatus: dispatch.status,
                  dataUrl: snapshotDataUrl,
                  compressedKb,
                };

                setSnapshots((prev) => [entry, ...prev]);
                setBannerToast(`${dispatch.status} (${settingsRef.current.cameraLabel})`);
                onAddTrace({
                  id: `${nowMs}-trig`,
                  timestampMs: nowMs,
                  event: `Motion Trigger (${res.changedAreaPercent.toFixed(1)}%)`,
                  tier: dispatch.tier,
                  detail: dispatch.detail,
                });
              })();
            }
          }
        }
      }

      rafId = window.requestAnimationFrame(renderLoop);
    };

    rafId = window.requestAnimationFrame(renderLoop);
    return () => window.cancelAnimationFrame(rafId);
  }, [useLiveWebcam, injectIntruderFrames, injectExposureFrames, onAddTrace, onTelemetryUpdate]);

  const handleToggleArm = () => {
    if (monitoringState === 'IDLE') {
      if (settings.exitDelayEnabled && settings.exitDelaySeconds > 0) {
        setExitCountdown(settings.exitDelaySeconds);
        setMonitoringState('EXIT_DELAY');
      } else {
        detectorRef.current.reset();
        setMonitoringState('ARMED');
        if (settings.stealthAutoActivate) setStealthActive(true);
      }
    } else {
      setMonitoringState('IDLE');
      setExitCountdown(0);
      setStealthActive(false);
      setLiveBox(null);
      detectorRef.current.reset();
      onTelemetryUpdate({
        changedAreaPercent: 0,
        meanLumaShift: 0,
        globalExposureSuppressed: false,
        consecutiveMotionFrames: 0,
        cooldownRemainingSeconds: 0,
        fps: 0,
        boundingBox: null,
      });
    }
  };

  const runDiagnostic = async (tier: 'WIFI' | 'TELCO_DATA' | 'SMS') => {
    if (diagnosticBusy) return;
    setDiagnosticBusy(true);
    const id = `${Date.now()}`;

    try {
      if (tier === 'WIFI') {
        if (!networkEnv.wifiConnected || !networkEnv.wifiValidated) {
          setDiagnosticLogs((prev) => [
            {
              id,
              tier: 'Tier 1: Wi-Fi',
              ok: false,
              msg: 'Wi-Fi lacks NET_CAPABILITY_VALIDATED (simulated ISP cable cut or offline).',
            },
            ...prev.slice(0, 6),
          ]);
        } else if (
          (settings.alertChannel === 'TELEGRAM' || settings.alertChannel === 'BOTH') &&
          settings.telegramBotToken.trim() &&
          settings.telegramChatId.trim()
        ) {
          const sampleImg = canvasRef.current?.toDataURL('image/jpeg', 0.75) || '';
          const resp = await fetch('/api/telegram/send-photo', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              botToken: settings.telegramBotToken,
              chatId: settings.telegramChatId,
              caption: `HomeAlert Diagnostic Test (Tier 1 Wi-Fi) · ${settings.cameraLabel}`,
              imageBase64: sampleImg,
            }),
          });
          const data = await resp.json();
          setDiagnosticLogs((prev) => [
            {
              id,
              tier: 'Tier 1: Wi-Fi (Live Telegram)',
              ok: Boolean(data.ok),
              msg: data.ok
                ? 'Live Telegram Bot sendPhoto succeeded!'
                : data.description || data.error || 'Telegram API rejected request',
            },
            ...prev.slice(0, 6),
          ]);
        } else {
          setDiagnosticLogs((prev) => [
            {
              id,
              tier: 'Tier 1: Wi-Fi',
              ok: true,
              msg: `Validated Wi-Fi route ready for ${settings.alertChannel}. (Add real Bot Token + Chat ID to send live Telegram test.)`,
            },
            ...prev.slice(0, 6),
          ]);
        }
      } else if (tier === 'TELCO_DATA') {
        if (settings.networkMode === 'WIFI_ONLY') {
          setDiagnosticLogs((prev) => [
            { id, tier: 'Tier 2: Telco Data', ok: false, msg: 'Disabled in Mode A (Wi-Fi Only).' },
            ...prev.slice(0, 6),
          ]);
        } else if (!networkEnv.cellularAvailable) {
          setDiagnosticLogs((prev) => [
            { id, tier: 'Tier 2: Telco Data', ok: false, msg: 'Cellular data unavailable.' },
            ...prev.slice(0, 6),
          ]);
        } else {
          setDiagnosticLogs((prev) => [
            {
              id,
              tier: 'Tier 2: Telco Data',
              ok: true,
              msg: `Acquired TRANSPORT_CELLULAR via ${networkEnv.simName} and verified ~185 KB socket upload.`,
            },
            ...prev.slice(0, 6),
          ]);
        }
      } else {
        if (settings.networkMode === 'WIFI_ONLY') {
          setDiagnosticLogs((prev) => [
            { id, tier: 'Tier 3: Telco SMS', ok: false, msg: 'Disabled in Mode A (Wi-Fi Only).' },
            ...prev.slice(0, 6),
          ]);
        } else if (!settings.smsFallbackNumber.trim()) {
          setDiagnosticLogs((prev) => [
            { id, tier: 'Tier 3: Telco SMS', ok: false, msg: 'Enter an SMS Fallback Phone Number first.' },
            ...prev.slice(0, 6),
          ]);
        } else {
          setDiagnosticLogs((prev) => [
            {
              id,
              tier: 'Tier 3: Telco SMS',
              ok: true,
              msg: `SmsManager queued diagnostic SMS to ${settings.smsFallbackNumber} via ${networkEnv.simName}.`,
            },
            ...prev.slice(0, 6),
          ]);
        }
      }
    } finally {
      setDiagnosticBusy(false);
    }
  };

  const statusText = networkEnv.wifiValidated
    ? 'Tier 1 Ready · Wi-Fi Validated'
    : settings.networkMode === 'WIFI_ONLY'
      ? 'Mode A · Wi-Fi Unvalidated'
      : networkEnv.cellularAvailable
        ? 'Tier 2 Ready · Telco Data Failover'
        : settings.smsFallbackNumber.trim()
          ? 'Tier 3 Ready · Telco SMS Standby'
          : 'Offline · Local Save Only';

  return (
    <div className="flex flex-col items-center">
      {/* Hidden analysis & video elements */}
      <video ref={videoRef} playsInline muted className="hidden" />
      <canvas ref={analysisCanvasRef} width={160} height={120} className="hidden" />

      {/* Android Device Shell */}
      <div className="relative w-full max-w-[390px] h-[740px] rounded-[34px] border-[3px] border-slate-700/80 bg-[#0B0E14] shadow-2xl overflow-hidden flex flex-col">
        {/* Android Status Bar */}
        <div className="flex items-center justify-between px-6 py-2.5 bg-[#131822] text-[11px] font-mono tabular-nums text-slate-400 shrink-0">
          <span>01:32</span>
          <div className="w-16 h-3.5 rounded-full bg-black/80 border border-slate-800" />
          <div className="flex items-center gap-2">
            <span>{networkEnv.wifiConnected ? 'Wi-Fi' : 'No Wi-Fi'}</span>
            <span>{networkEnv.cellularAvailable ? '5G' : 'No SIM'}</span>
          </div>
        </div>

        {/* Material 3 Top App Bar */}
        <div className="flex items-center justify-between px-4 py-3 bg-[#131822] border-b border-slate-800/80 shrink-0">
          <span className="text-base font-semibold text-slate-100 tracking-tight">HomeAlert</span>
          <span className="text-[11px] font-mono text-slate-400">
            {monitoringState === 'ARMED'
              ? 'ARMED'
              : monitoringState === 'EXIT_DELAY'
                ? `ARMING ${exitCountdown}s`
                : 'STANDBY'}
          </span>
        </div>

        {/* Toast Snackbar */}
        {bannerToast && (
          <div className="mx-3 mt-2 px-3 py-2 rounded-lg bg-sky-950/90 border border-sky-700/60 text-xs text-sky-100 flex items-center justify-between shrink-0">
            <span className="truncate">{bannerToast}</span>
            <button
              onClick={() => setBannerToast(null)}
              className="ml-2 text-sky-300 hover:text-white shrink-0"
              aria-label="Dismiss notification"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* Scrollable Screen Content */}
        <div className="flex-1 overflow-y-auto px-3.5 py-3 space-y-3">
          {activeTab === 'MONITOR' && (
            <>
              {/* Network Status Row */}
              <div className="flex items-center justify-between px-3 py-2.5 rounded-xl bg-[#161C28] border border-slate-800">
                <div className="flex items-center gap-2.5 min-w-0">
                  {networkEnv.wifiValidated ? (
                    <Wifi className="w-4 h-4 text-emerald-400 shrink-0" />
                  ) : (
                    <WifiOff className="w-4 h-4 text-amber-400 shrink-0" />
                  )}
                  <div className="min-w-0">
                    <div className="text-xs font-medium text-slate-100 truncate">{statusText}</div>
                    <div className="text-[11px] text-slate-400 truncate">
                      {settings.networkMode === 'WIFI_ONLY' ? 'Mode A: Wi-Fi Only' : 'Mode B: Auto-Failover'} ·{' '}
                      {settings.alertChannel}
                    </div>
                  </div>
                </div>
              </div>

              {/* CameraX Preview Viewport */}
              <div className="relative rounded-2xl overflow-hidden bg-black border border-slate-800 aspect-[16/10]">
                <canvas ref={canvasRef} width={360} height={225} className="w-full h-full object-cover" />

                {/* Green Dual-Line Hysteresis Overlay & Live Bounding Box (Setup AND Armed) */}
                {monitoringState !== 'EXIT_DELAY' && (
                  <div className="absolute inset-0 pointer-events-none">
                    {/* Vertical dual-line hysteresis borders */}
                    {(settings.zoneLayout === 'SIX_ZONES' ? [1 / 3, 2 / 3] : [0.5]).map(
                      (center, idx) => (
                        <React.Fragment key={`v-${idx}`}>
                          <div
                            style={{
                              left: `${(center - 0.03) * 100}%`,
                              width: '6%',
                            }}
                            className="absolute inset-y-0 bg-emerald-500/10 border-x border-dashed border-emerald-400/75"
                          />
                        </React.Fragment>
                      )
                    )}

                    {/* Horizontal dual-line hysteresis border */}
                    <div
                      style={{
                        top: `${(0.5 - 0.03) * 100}%`,
                        height: '6%',
                      }}
                      className="absolute inset-x-0 bg-emerald-500/10 border-y border-dashed border-emerald-400/75"
                    />

                    {/* Live Motion Bounding Box */}
                    {liveBox && (
                      <>
                        <div
                          style={{
                            left: `${liveBox.left * 100}%`,
                            top: `${liveBox.top * 100}%`,
                            width: `${Math.max(4, (liveBox.right - liveBox.left) * 100)}%`,
                            height: `${Math.max(4, (liveBox.bottom - liveBox.top) * 100)}%`,
                          }}
                          className={`absolute border-2 ${
                            liveBox.inGuardband ? 'border-amber-400' : 'border-emerald-400'
                          }`}
                        >
                          <span className="absolute -top-4 left-0 text-[9px] font-mono px-1 bg-black/85 text-emerald-300 whitespace-nowrap">
                            {liveBox.crossedFromLabel
                              ? `${liveBox.crossedFromLabel}→${liveBox.zoneLabel}`
                              : liveBox.zoneLabel}
                          </span>
                        </div>
                        <div
                          style={{
                            left: `${liveBox.centroidX * 100}%`,
                            top: `${liveBox.centroidY * 100}%`,
                          }}
                          className={`absolute w-2 h-2 -translate-x-1/2 -translate-y-1/2 rounded-full ${
                            liveBox.inGuardband ? 'bg-amber-400' : 'bg-emerald-400'
                          }`}
                        />
                      </>
                    )}

                    {/* Bottom Setup / Armed Telemetry Strip */}
                    <div className="absolute inset-x-0 bottom-0 bg-black/65 px-2.5 py-1 flex items-center justify-between text-[10px] font-mono">
                      <span className="text-emerald-400 truncate">
                        {monitoringState === 'ARMED' ? 'ARMED' : 'SETUP'} ·{' '}
                        {liveBox
                          ? liveBox.crossedFromLabel
                            ? `${liveBox.crossedFromLabel}→${liveBox.zoneLabel}`
                            : liveBox.zoneLabel
                          : 'Green Grid Ready'}
                      </span>
                      <span className="text-slate-300 shrink-0 ml-2">
                        {settings.zoneLayout === 'SIX_ZONES' ? '6 Zones (3×2)' : '4 Zones (2×2)'}
                      </span>
                    </div>
                  </div>
                )}

                {flashBurstActive && (
                  <div className="absolute inset-0 bg-white/95 pointer-events-none transition-opacity duration-150" />
                )}

                {monitoringState === 'EXIT_DELAY' && (
                  <div className="absolute inset-0 bg-black/75 flex flex-col items-center justify-center text-center p-4">
                    <div className="text-4xl font-bold font-mono tabular-nums text-amber-400">
                      {exitCountdown}s
                    </div>
                    <div className="text-xs text-slate-200 mt-1">
                      Exit room now — arming automatically
                    </div>
                  </div>
                )}
              </div>

              {/* Camera Source & Synthetic Test Triggers */}
              <div className="flex items-center justify-between gap-2">
                <button
                  onClick={() => setUseLiveWebcam((v) => !v)}
                  className="flex-1 px-2.5 py-2 rounded-lg text-[11px] font-medium bg-[#161C28] border border-slate-800 text-slate-200 hover:border-sky-500/60 transition-colors whitespace-nowrap"
                >
                  {useLiveWebcam ? 'Switch to Synthetic Feed' : 'Use Real Webcam'}
                </button>
                <button
                  onClick={() => setInjectIntruderFrames((n) => n + 1)}
                  disabled={useLiveWebcam}
                  className="px-2.5 py-2 rounded-lg text-[11px] font-medium bg-[#161C28] border border-slate-800 text-amber-300 hover:border-amber-500/60 disabled:opacity-40 transition-colors whitespace-nowrap"
                >
                  Test Intruder
                </button>
                <button
                  onClick={() => setInjectExposureFrames((n) => n + 1)}
                  disabled={useLiveWebcam}
                  className="px-2.5 py-2 rounded-lg text-[11px] font-medium bg-[#161C28] border border-slate-800 text-sky-300 hover:border-sky-500/60 disabled:opacity-40 transition-colors whitespace-nowrap"
                >
                  Cloud Jump
                </button>
              </div>
              {webcamError && <p className="text-[11px] text-amber-400">{webcamError}</p>}

              {/* Controls Card */}
              <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3.5 space-y-3.5">
                <div>
                  <label className="block text-[11px] text-slate-400 mb-1">Camera / Location Label</label>
                  <input
                    type="text"
                    value={settings.cameraLabel}
                    onChange={(e) => onUpdateSettings({ cameraLabel: e.target.value })}
                    className="w-full px-3 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100 focus:outline-none focus:border-sky-400"
                  />
                </div>

                <div>
                  <div className="flex justify-between text-xs mb-1">
                    <span className="text-slate-300">Sensitivity Threshold</span>
                    <span className="font-mono tabular-nums text-sky-400">
                      {settings.sensitivityPercent.toFixed(1)}% area
                    </span>
                  </div>
                  <input
                    type="range"
                    min={1}
                    max={25}
                    step={0.5}
                    value={settings.sensitivityPercent}
                    onChange={(e) => onUpdateSettings({ sensitivityPercent: parseFloat(e.target.value) })}
                    className="w-full accent-sky-400"
                  />
                </div>

                <div>
                  <div className="flex justify-between text-xs mb-1">
                    <span className="text-slate-300">Cooldown Timer</span>
                    <span className="font-mono tabular-nums text-sky-400">
                      {settings.cooldownSeconds}s
                    </span>
                  </div>
                  <input
                    type="range"
                    min={5}
                    max={120}
                    step={1}
                    value={settings.cooldownSeconds}
                    onChange={(e) => onUpdateSettings({ cooldownSeconds: parseInt(e.target.value, 10) })}
                    className="w-full accent-sky-400"
                  />
                </div>

                <label className="flex items-center justify-between gap-2 cursor-pointer">
                  <div>
                    <div className="text-xs font-medium text-slate-200">
                      Exit Delay Countdown ({settings.exitDelaySeconds}s)
                    </div>
                    <div className="text-[11px] text-slate-400">Grace period before arming</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={settings.exitDelayEnabled}
                    onChange={(e) => onUpdateSettings({ exitDelayEnabled: e.target.checked })}
                    className="w-4 h-4 accent-sky-400"
                  />
                </label>

                <label className="flex items-center justify-between gap-2 cursor-pointer">
                  <div>
                    <div className="text-xs font-medium text-slate-200">Auto Stealth / Blackout Mode</div>
                    <div className="text-[11px] text-slate-400">OLED #000000 screen on arm</div>
                  </div>
                  <input
                    type="checkbox"
                    checked={settings.stealthAutoActivate}
                    onChange={(e) => onUpdateSettings({ stealthAutoActivate: e.target.checked })}
                    className="w-4 h-4 accent-sky-400"
                  />
                </label>

                <label className="flex items-center justify-between gap-2 cursor-pointer">
                  <div>
                    <div className="text-xs font-medium text-slate-200">Require Region Crossing</div>
                    <div className="text-[11px] text-slate-400">
                      Cross both parallel lines of any border
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    checked={settings.requireRegionCrossing}
                    onChange={(e) => onUpdateSettings({ requireRegionCrossing: e.target.checked })}
                    className="w-4 h-4 accent-sky-400"
                  />
                </label>

                <div className="flex items-center justify-between gap-2">
                  <div>
                    <div className="text-xs font-medium text-slate-200">Zone Grid Layout</div>
                    <div className="text-[11px] text-slate-400">Dual-line hysteresis borders</div>
                  </div>
                  <div className="inline-flex rounded-lg bg-[#0B0E14] p-0.5 border border-slate-700">
                    <button
                      type="button"
                      onClick={() => onUpdateSettings({ zoneLayout: 'FOUR_ZONES' })}
                      className={`px-2.5 py-1 rounded-md text-[11px] font-semibold transition-colors whitespace-nowrap ${
                        settings.zoneLayout === 'FOUR_ZONES'
                          ? 'bg-sky-600 text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      4 (2×2)
                    </button>
                    <button
                      type="button"
                      onClick={() => onUpdateSettings({ zoneLayout: 'SIX_ZONES' })}
                      className={`px-2.5 py-1 rounded-md text-[11px] font-semibold transition-colors whitespace-nowrap ${
                        settings.zoneLayout === 'SIX_ZONES'
                          ? 'bg-sky-600 text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      6 (3×2)
                    </button>
                  </div>
                </div>

                <div className="flex items-center justify-between gap-2">
                  <div>
                    <div className="text-xs font-medium text-slate-200">Camera Flash</div>
                    <div className="text-[11px] text-slate-400">Rear LED flash on capture</div>
                  </div>
                  <div className="inline-flex rounded-lg bg-[#0B0E14] p-0.5 border border-slate-700">
                    <button
                      type="button"
                      onClick={() => onUpdateSettings({ deterrentFlashEnabled: true })}
                      className={`px-3 py-1 rounded-md text-[11px] font-semibold transition-colors ${
                        settings.deterrentFlashEnabled
                          ? 'bg-sky-600 text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      Yes
                    </button>
                    <button
                      type="button"
                      onClick={() => onUpdateSettings({ deterrentFlashEnabled: false })}
                      className={`px-3 py-1 rounded-md text-[11px] font-semibold transition-colors ${
                        !settings.deterrentFlashEnabled
                          ? 'bg-slate-700 text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      No
                    </button>
                  </div>
                </div>

                <div className="flex items-center justify-between gap-2">
                  <div>
                    <div className="text-xs font-medium text-slate-200">Shutter Click</div>
                    <div className="text-[11px] text-slate-400">Audible click on capture</div>
                  </div>
                  <div className="inline-flex rounded-lg bg-[#0B0E14] p-0.5 border border-slate-700">
                    <button
                      type="button"
                      onClick={() => onUpdateSettings({ deterrentShutterSoundEnabled: true })}
                      className={`px-3 py-1 rounded-md text-[11px] font-semibold transition-colors ${
                        settings.deterrentShutterSoundEnabled
                          ? 'bg-sky-600 text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      Yes
                    </button>
                    <button
                      type="button"
                      onClick={() => onUpdateSettings({ deterrentShutterSoundEnabled: false })}
                      className={`px-3 py-1 rounded-md text-[11px] font-semibold transition-colors ${
                        !settings.deterrentShutterSoundEnabled
                          ? 'bg-slate-700 text-white'
                          : 'text-slate-400 hover:text-slate-200'
                      }`}
                    >
                      No
                    </button>
                  </div>
                </div>
              </div>

              {/* Primary CTA Row */}
              <div className="flex items-center gap-2 pt-1 pb-2">
                <button
                  onClick={handleToggleArm}
                  className={`flex-1 h-12 rounded-xl text-xs font-semibold text-white flex items-center justify-center gap-2 transition-colors ${
                    monitoringState === 'IDLE'
                      ? 'bg-emerald-600 hover:bg-emerald-500'
                      : 'bg-rose-600 hover:bg-rose-500'
                  }`}
                >
                  {monitoringState === 'IDLE' ? (
                    <>
                      <Play className="w-4 h-4" /> Start Monitoring
                    </>
                  ) : (
                    <>
                      <Square className="w-4 h-4" />
                      {monitoringState === 'EXIT_DELAY'
                        ? `Cancel (${exitCountdown}s)`
                        : 'Stop Monitoring'}
                    </>
                  )}
                </button>
                <button
                  onClick={() => setStealthActive(true)}
                  title="Enter Stealth / Blackout Mode"
                  className="h-12 px-3.5 rounded-xl bg-[#161C28] border border-slate-700 text-slate-200 hover:border-sky-400 flex items-center justify-center"
                >
                  <Sun className="w-4 h-4" />
                </button>
              </div>
            </>
          )}

          {activeTab === 'GALLERY' && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-slate-100">MediaStore Gallery</h3>
                  <p className="text-[11px] text-slate-400">
                    Pictures/HomeAlert · {snapshots.length} captures
                  </p>
                </div>
              </div>

              {snapshots.length === 0 ? (
                <div className="py-16 text-center text-xs text-slate-400 border border-dashed border-slate-800 rounded-2xl px-4">
                  No snapshots captured yet. Start Monitoring on the Monitor tab and trigger motion
                  (or click Test Intruder) to capture a MediaStore JPEG.
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-2.5">
                  {snapshots.map((item) => (
                    <div
                      key={item.id}
                      className="rounded-xl bg-[#161C28] border border-slate-800 overflow-hidden flex flex-col"
                    >
                      <button
                        onClick={() => setViewingSnapshot(item)}
                        className="aspect-[4/3] w-full bg-black overflow-hidden"
                      >
                        <img
                          src={item.dataUrl}
                          alt={item.filename}
                          className="w-full h-full object-cover"
                        />
                      </button>
                      <div className="p-2 space-y-1">
                        <div className="text-[11px] font-mono tabular-nums text-slate-200">
                          {new Date(item.timestampMs).toLocaleTimeString()}
                        </div>
                        <div
                          className={`text-[10px] font-medium truncate ${
                            item.deliveryTier === 'WIFI'
                              ? 'text-emerald-400'
                              : item.deliveryTier === 'TELCO_DATA'
                                ? 'text-sky-400'
                                : item.deliveryTier === 'TELCO_SMS'
                                  ? 'text-amber-400'
                                  : 'text-slate-400'
                          }`}
                        >
                          {item.deliveryStatus}
                        </div>
                        <div className="flex items-center justify-between pt-0.5">
                          <span className="text-[10px] text-slate-500 font-mono">
                            {item.compressedKb} KB
                          </span>
                          <button
                            onClick={() =>
                              setSnapshots((prev) => prev.filter((s) => s.id !== item.id))
                            }
                            className="text-slate-400 hover:text-rose-400 p-1"
                            aria-label="Delete snapshot"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {activeTab === 'SETTINGS' && (
            <div className="space-y-3 pb-3">
              {/* Network Mode */}
              <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3 space-y-2">
                <div className="text-xs font-semibold text-slate-100">Network Mode</div>
                {(
                  [
                    ['WIFI_ONLY', 'Mode A: Wi-Fi Connected Only', 'Strictly Wi-Fi; no cellular or SMS'],
                    [
                      'SMART_AUTO_FAILOVER',
                      'Mode B: Smart Auto-Failover',
                      'Wi-Fi (4s) → Telco Data → Telco SMS',
                    ],
                  ] as Array<[NetworkMode, string, string]>
                ).map(([mode, title, desc]) => (
                  <label
                    key={mode}
                    className="flex items-start gap-2.5 p-2 rounded-lg bg-[#0B0E14] border border-slate-800 cursor-pointer"
                  >
                    <input
                      type="radio"
                      name="networkMode"
                      checked={settings.networkMode === mode}
                      onChange={() => onUpdateSettings({ networkMode: mode })}
                      className="mt-0.5 accent-sky-400"
                    />
                    <div>
                      <div className="text-xs font-medium text-slate-200">{title}</div>
                      <div className="text-[11px] text-slate-400">{desc}</div>
                    </div>
                  </label>
                ))}
              </div>

              {/* Alert Channel */}
              <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3 space-y-2">
                <div className="text-xs font-semibold text-slate-100">Alert Channel</div>
                <div className="grid grid-cols-3 gap-1.5">
                  {(['TELEGRAM', 'GMAIL', 'BOTH'] as AlertChannel[]).map((ch) => (
                    <button
                      key={ch}
                      onClick={() => onUpdateSettings({ alertChannel: ch })}
                      className={`py-2 px-2 rounded-lg text-[11px] font-medium border transition-colors ${
                        settings.alertChannel === ch
                          ? 'bg-sky-500/20 border-sky-400 text-sky-200'
                          : 'bg-[#0B0E14] border-slate-800 text-slate-400'
                      }`}
                    >
                      {ch === 'BOTH' ? 'Both' : ch === 'TELEGRAM' ? 'Telegram' : 'Gmail'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Telegram Inputs */}
              {(settings.alertChannel === 'TELEGRAM' || settings.alertChannel === 'BOTH') && (
                <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-slate-100">Telegram Bot</span>
                    <button
                      onClick={() => setShowBotFatherModal(true)}
                      className="text-[11px] text-sky-400 hover:underline flex items-center gap-1"
                    >
                      <HelpCircle className="w-3.5 h-3.5" /> @BotFather Guide
                    </button>
                  </div>
                  <input
                    type="text"
                    placeholder="Bot Token (optional for live test)"
                    value={settings.telegramBotToken}
                    onChange={(e) => onUpdateSettings({ telegramBotToken: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100 font-mono"
                  />
                  <input
                    type="text"
                    placeholder="Chat ID (numeric)"
                    value={settings.telegramChatId}
                    onChange={(e) => onUpdateSettings({ telegramChatId: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100 font-mono"
                  />
                </div>
              )}

              {/* Gmail Inputs */}
              {(settings.alertChannel === 'GMAIL' || settings.alertChannel === 'BOTH') && (
                <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3 space-y-2">
                  <div className="text-xs font-semibold text-slate-100">Gmail TLS SMTP</div>
                  <input
                    type="email"
                    placeholder="Sender Gmail"
                    value={settings.gmailSender}
                    onChange={(e) => onUpdateSettings({ gmailSender: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100"
                  />
                  <input
                    type="password"
                    placeholder="16-char Google App Password"
                    value={settings.gmailAppPassword}
                    onChange={(e) => onUpdateSettings({ gmailAppPassword: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100 font-mono"
                  />
                  <input
                    type="text"
                    placeholder="Recipient Email(s)"
                    value={settings.gmailRecipients}
                    onChange={(e) => onUpdateSettings({ gmailRecipients: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100"
                  />
                </div>
              )}

              {/* SMS Fallback */}
              {settings.networkMode === 'SMART_AUTO_FAILOVER' && (
                <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3 space-y-2">
                  <div className="text-xs font-semibold text-slate-100">
                    Tier 3 SMS Fallback (90s Anti-Flood)
                  </div>
                  <input
                    type="tel"
                    placeholder="+15551234567"
                    value={settings.smsFallbackNumber}
                    onChange={(e) => onUpdateSettings({ smsFallbackNumber: e.target.value })}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100 font-mono"
                  />
                  <select
                    value={settings.selectedSimSlot}
                    onChange={(e) => {
                      const slot = Number(e.target.value);
                      onUpdateSettings({ selectedSimSlot: slot });
                      onUpdateNetworkEnv({
                        simName: slot === 1 ? 'SIM 1 (T-Mobile 5G)' : 'SIM 2 (Verizon LTE)',
                      });
                    }}
                    className="w-full px-2.5 py-2 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs text-slate-100"
                  >
                    <option value={1}>SIM 1 (T-Mobile 5G)</option>
                    <option value={2}>SIM 2 (Verizon LTE)</option>
                  </select>
                </div>
              )}

              {/* Pre-Arm Diagnostic Test Buttons */}
              <div className="rounded-2xl bg-[#161C28] border border-slate-800 p-3 space-y-2">
                <div className="text-xs font-semibold text-slate-100">Pre-Arm Diagnostics</div>
                <button
                  onClick={() => runDiagnostic('WIFI')}
                  disabled={diagnosticBusy}
                  className="w-full py-2 rounded-lg bg-sky-600 hover:bg-sky-500 text-xs font-medium text-white transition-colors"
                >
                  Test Wi-Fi Alert
                </button>
                <button
                  onClick={() => runDiagnostic('TELCO_DATA')}
                  disabled={diagnosticBusy || settings.networkMode === 'WIFI_ONLY'}
                  className="w-full py-2 rounded-lg bg-sky-700 hover:bg-sky-600 disabled:opacity-40 text-xs font-medium text-white transition-colors"
                >
                  Test Telco Data Alert
                </button>
                <button
                  onClick={() => runDiagnostic('SMS')}
                  disabled={diagnosticBusy || settings.networkMode === 'WIFI_ONLY'}
                  className="w-full py-2 rounded-lg bg-amber-600 hover:bg-amber-500 disabled:opacity-40 text-xs font-medium text-white transition-colors"
                >
                  Test Fallback SMS
                </button>
                {diagnosticLogs.length > 0 && (
                  <div className="pt-1 space-y-1.5">
                    {diagnosticLogs.map((log) => (
                      <div
                        key={log.id}
                        className={`text-[11px] leading-snug ${
                          log.ok ? 'text-emerald-400' : 'text-rose-400'
                        }`}
                      >
                        {log.ok ? 'PASS' : 'FAIL'} · {log.tier}: {log.msg}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Material 3 Bottom Navigation Bar (3 Tabs) */}
        <div className="grid grid-cols-3 h-16 bg-[#131822] border-t border-slate-800 shrink-0">
          {(
            [
              ['MONITOR', 'Monitor', Camera],
              ['GALLERY', 'Gallery', ImageIcon],
              ['SETTINGS', 'Settings', Settings],
            ] as Array<[PhoneTab, string, React.FC<{ className?: string }>]>
          ).map(([tab, label, Icon]) => {
            const active = activeTab === tab;
            return (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`flex flex-col items-center justify-center gap-1 transition-colors ${
                  active ? 'text-sky-400' : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-[11px] font-medium">{label}</span>
              </button>
            );
          })}
        </div>

        {/* Stealth / Blackout Pure #000000 OLED Overlay */}
        {stealthActive && (
          <div
            onDoubleClick={() => setStealthActive(false)}
            onMouseDown={() => {
              longPressTimerRef.current = window.setTimeout(() => {
                setStealthActive(false);
              }, 1100);
            }}
            onMouseUp={() => {
              if (longPressTimerRef.current) {
                window.clearTimeout(longPressTimerRef.current);
              }
            }}
            onTouchStart={() => {
              longPressTimerRef.current = window.setTimeout(() => {
                setStealthActive(false);
              }, 1100);
            }}
            onTouchEnd={() => {
              if (longPressTimerRef.current) {
                window.clearTimeout(longPressTimerRef.current);
              }
            }}
            className="absolute inset-0 z-50 bg-black flex flex-col items-center justify-center select-none cursor-pointer p-6 text-center"
          >
            <div className="flex items-center gap-2.5">
              <span
                className={`w-2.5 h-2.5 rounded-full animate-pulse ${
                  monitoringState === 'ARMED'
                    ? networkEnv.wifiValidated
                      ? 'bg-emerald-500/50'
                      : 'bg-amber-500/50'
                    : 'bg-sky-500/50'
                }`}
              />
              <span className="text-[11px] font-mono tracking-[0.2em] text-neutral-700">
                {monitoringState === 'ARMED' ? 'STEALTH ARMED' : 'STEALTH STANDBY'}
              </span>
            </div>
            <p className="mt-3 text-[11px] text-neutral-800">
              Brightness 0.01f · Double-click or hold 1.1s to unlock
            </p>
          </div>
        )}

        {/* Full-Screen Snapshot Viewer Modal */}
        {viewingSnapshot && (
          <div className="absolute inset-0 z-40 bg-black/95 flex flex-col p-4">
            <div className="flex items-center justify-between pb-3 border-b border-slate-800">
              <div className="min-w-0">
                <div className="text-xs font-medium text-white truncate">
                  {viewingSnapshot.filename}
                </div>
                <div className="text-[11px] text-emerald-400">{viewingSnapshot.deliveryStatus}</div>
              </div>
              <button
                onClick={() => setViewingSnapshot(null)}
                className="p-2 text-slate-300 hover:text-white"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="flex-1 flex items-center justify-center py-4">
              <img
                src={viewingSnapshot.dataUrl}
                alt={viewingSnapshot.filename}
                className="max-h-full max-w-full rounded-lg object-contain"
              />
            </div>
          </div>
        )}

        {/* @BotFather Setup Modal */}
        {showBotFatherModal && (
          <div className="absolute inset-0 z-40 bg-black/80 flex items-center justify-center p-5">
            <div className="w-full rounded-2xl bg-[#161C28] border border-slate-700 p-4 space-y-3">
              <div className="flex items-center justify-between">
                <h4 className="text-sm font-semibold text-white">@BotFather Setup Guide</h4>
                <button
                  onClick={() => setShowBotFatherModal(false)}
                  className="text-slate-400 hover:text-white"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
              <ol className="text-xs text-slate-300 space-y-1.5 list-decimal list-inside">
                <li>Message @BotFather on Telegram and send /newbot.</li>
                <li>Copy the HTTP API Bot Token it gives you.</li>
                <li>Open your new bot chat and tap Start (/start).</li>
                <li>Message @userinfobot to get your numeric Chat ID.</li>
              </ol>
              <button
                onClick={() => setShowBotFatherModal(false)}
                className="w-full py-2 rounded-lg bg-sky-600 text-xs font-medium text-white"
              >
                Done
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
