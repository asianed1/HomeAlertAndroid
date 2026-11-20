import { useCallback, useEffect, useState } from 'react';
import { Download } from 'lucide-react';
import { AndroidPhoneSimulator } from './components/AndroidPhoneSimulator';
import { AndroidSourceExplorer } from './components/AndroidSourceExplorer';
import { ArchitectureAndBuildView } from './components/ArchitectureAndBuildView';
import {
  AndroidFileEntry,
  EmulatorSettings,
  FailoverTraceEntry,
  MotionTelemetryState,
  SimNetworkEnvironment,
  StudioView,
} from './types';

export default function App() {
  const [view, setView] = useState<StudioView>('SIMULATOR');
  const [androidFiles, setAndroidFiles] = useState<AndroidFileEntry[]>([]);
  const [loadingFiles, setLoadingFiles] = useState<boolean>(true);

  const [settings, setSettings] = useState<EmulatorSettings>({
    cameraLabel: 'Front Hallway',
    sensitivityPercent: 5.0,
    cooldownSeconds: 15,
    exitDelayEnabled: true,
    exitDelaySeconds: 15,
    stealthAutoActivate: false,
    networkMode: 'SMART_AUTO_FAILOVER',
    alertChannel: 'TELEGRAM',
    telegramBotToken: '',
    telegramChatId: '',
    gmailSender: '',
    gmailAppPassword: '',
    gmailRecipients: '',
    smsFallbackNumber: '+15551234567',
    selectedSimSlot: 1,
    deterrentFlashEnabled: true,
    deterrentShutterSoundEnabled: true,
    requireRegionCrossing: false,
    zoneLayout: 'FOUR_ZONES',
  });

  const [networkEnv, setNetworkEnv] = useState<SimNetworkEnvironment>({
    wifiConnected: true,
    wifiValidated: true,
    cellularAvailable: true,
    simName: 'SIM 1 (T-Mobile 5G)',
  });

  const [telemetry, setTelemetry] = useState<MotionTelemetryState>({
    changedAreaPercent: 0,
    meanLumaShift: 0,
    globalExposureSuppressed: false,
    consecutiveMotionFrames: 0,
    cooldownRemainingSeconds: 0,
    fps: 0,
    boundingBox: null,
  });

  const [traces, setTraces] = useState<FailoverTraceEntry[]>([]);

  useEffect(() => {
    fetch('/api/android-files')
      .then((res) => res.json())
      .then((data) => {
        setAndroidFiles(data.files || []);
      })
      .catch(() => {
        setAndroidFiles([]);
      })
      .finally(() => setLoadingFiles(false));
  }, []);

  const handleUpdateSettings = useCallback((patch: Partial<EmulatorSettings>) => {
    setSettings((prev) => ({ ...prev, ...patch }));
  }, []);

  const handleUpdateNetworkEnv = useCallback((patch: Partial<SimNetworkEnvironment>) => {
    setNetworkEnv((prev) => ({ ...prev, ...patch }));
  }, []);

  const handleAddTrace = useCallback((entry: FailoverTraceEntry) => {
    setTraces((prev) => [entry, ...prev.slice(0, 14)]);
  }, []);

  const handleDownloadZip = () => {
    window.location.href = '/api/android-project.zip';
  };

  return (
    <div className="min-h-screen bg-[#0B0E14] text-slate-100 flex flex-col">
      {/* Top Bar Contract: Zone 1 (Wordmark) — Zone 2 (4 single-line nav links) — Zone 3 (1 primary CTA) */}
      <header className="border-b border-slate-800/80 bg-[#0B0E14]/95 sticky top-0 z-30 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between gap-8">
          <a
            href="#simulator"
            onClick={(e) => {
              e.preventDefault();
              setView('SIMULATOR');
            }}
            className="text-lg font-bold tracking-tight text-slate-100 whitespace-nowrap shrink-0"
          >
            HomeAlertAndroid
          </a>

          <nav className="hidden md:flex items-center gap-6 text-sm font-medium">
            {(
              [
                ['SIMULATOR', 'Live Simulator'],
                ['SOURCE', 'Android Source'],
                ['ARCHITECTURE', 'Failover & Engine'],
                ['BUILD', 'APK Build Guide'],
              ] as Array<[StudioView, string]>
            ).map(([key, label]) => (
              <button
                key={key}
                onClick={() => setView(key)}
                className={`py-1 whitespace-nowrap shrink-0 transition-colors border-b-2 ${
                  view === key
                    ? 'text-sky-400 border-sky-400'
                    : 'text-slate-400 border-transparent hover:text-slate-100'
                }`}
              >
                {label}
              </button>
            ))}
          </nav>

          <div className="flex items-center gap-3 shrink-0">
            <button
              onClick={handleDownloadZip}
              className="px-4 py-2 rounded-lg bg-sky-600 hover:bg-sky-500 text-xs font-semibold text-white flex items-center gap-2 transition-colors whitespace-nowrap shrink-0"
            >
              <Download className="w-3.5 h-3.5" />
              Download Android (.zip)
            </button>
          </div>
        </div>
      </header>

      {/* Mobile Secondary Navigation */}
      <div className="md:hidden border-b border-slate-800 px-4 py-2 flex items-center gap-4 overflow-x-auto text-xs">
        {(
          [
            ['SIMULATOR', 'Live Simulator'],
            ['SOURCE', 'Android Source'],
            ['ARCHITECTURE', 'Failover & Engine'],
            ['BUILD', 'APK Build Guide'],
          ] as Array<[StudioView, string]>
        ).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setView(key)}
            className={`py-1 whitespace-nowrap shrink-0 ${
              view === key ? 'text-sky-400 font-semibold' : 'text-slate-400'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Main Content */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-6 py-8">
        {view === 'SIMULATOR' && (
          <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
            {/* Left/Center: Interactive Android Material 3 Device */}
            <div className="lg:col-span-5 flex justify-center">
              <AndroidPhoneSimulator
                settings={settings}
                onUpdateSettings={handleUpdateSettings}
                networkEnv={networkEnv}
                onUpdateNetworkEnv={handleUpdateNetworkEnv}
                onAddTrace={handleAddTrace}
                onTelemetryUpdate={setTelemetry}
              />
            </div>

            {/* Right: Live Motion Engine & 3-Tier Network Failover Inspector */}
            <div className="lg:col-span-7 space-y-6">
              <div className="space-y-2">
                <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-slate-100 text-balance">
                  Kotlin, Jetpack Compose & CameraX Motion Security
                </h1>
                <p className="text-sm text-slate-400 leading-relaxed max-w-2xl">
                  Interactive emulator running the same Y-plane luma difference algorithm, global
                  exposure jump suppression, 2-frame debounce, Stealth Blackout OLED overlay, and
                  3-tier Wi-Fi → Telco Data → Telco SMS failover logic built into the native Android
                  project ({androidFiles.length} Gradle, Kotlin & JUnit4 files).
                </p>
              </div>

              {/* Live Motion Detector Metrics */}
              <div className="rounded-2xl border border-slate-800 bg-[#131822] p-5 space-y-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-slate-100">
                    CameraX YUV_420_888 Luma Engine State
                  </h2>
                  <span className="text-xs font-mono tabular-nums text-slate-400">
                    {telemetry.fps > 0 ? `${telemetry.fps.toFixed(1)} FPS` : 'Standby'}
                  </span>
                </div>

                <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 pt-1">
                  <div>
                    <div className="text-[11px] text-slate-400">Changed Area</div>
                    <div className="text-xl font-semibold font-mono tabular-nums text-slate-100 mt-0.5">
                      {telemetry.changedAreaPercent.toFixed(1)}%
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-slate-400">2-Frame Debounce</div>
                    <div className="text-xl font-semibold font-mono tabular-nums text-sky-400 mt-0.5">
                      {telemetry.consecutiveMotionFrames} / 2
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-slate-400">Active Zone / Crossing</div>
                    <div className="text-sm font-semibold font-mono text-emerald-400 mt-1.5">
                      {telemetry.boundingBox
                        ? telemetry.boundingBox.crossedFromLabel
                          ? `${telemetry.boundingBox.crossedFromLabel} → ${telemetry.boundingBox.zoneLabel}`
                          : telemetry.boundingBox.zoneLabel
                        : '—'}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-slate-400">Snapshot Cooldown</div>
                    <div className="text-xl font-semibold font-mono tabular-nums text-slate-100 mt-0.5">
                      {telemetry.cooldownRemainingSeconds}s
                    </div>
                  </div>
                </div>
              </div>

              {/* Network Environment Simulator */}
              <div className="rounded-2xl border border-slate-800 bg-[#131822] p-5 space-y-4">
                <div>
                  <h2 className="text-sm font-semibold text-slate-100">
                    Simulate Home Network Failure Scenarios
                  </h2>
                  <p className="text-xs text-slate-400 mt-0.5">
                    Toggle connectivity states to test Mode A vs Mode B 3-tier failover routing live.
                  </p>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                  <button
                    onClick={() =>
                      setNetworkEnv((p) => ({ ...p, wifiConnected: !p.wifiConnected }))
                    }
                    className={`p-3 rounded-xl border text-left transition-colors ${
                      networkEnv.wifiConnected
                        ? 'bg-emerald-500/10 border-emerald-500/40 text-slate-100'
                        : 'bg-[#0B0E14] border-slate-800 text-slate-400'
                    }`}
                  >
                    <div className="text-xs font-semibold">Wi-Fi Radio</div>
                    <div className="text-[11px] mt-0.5 font-mono">
                      {networkEnv.wifiConnected ? 'Connected' : 'Disconnected'}
                    </div>
                  </button>

                  <button
                    onClick={() =>
                      setNetworkEnv((p) => ({ ...p, wifiValidated: !p.wifiValidated }))
                    }
                    className={`p-3 rounded-xl border text-left transition-colors ${
                      networkEnv.wifiValidated && networkEnv.wifiConnected
                        ? 'bg-emerald-500/10 border-emerald-500/40 text-slate-100'
                        : 'bg-amber-500/10 border-amber-500/40 text-amber-200'
                    }`}
                  >
                    <div className="text-xs font-semibold">ISP Outside Internet</div>
                    <div className="text-[11px] mt-0.5 font-mono">
                      {networkEnv.wifiValidated ? 'VALIDATED' : 'Cable Cut (No WAN)'}
                    </div>
                  </button>

                  <button
                    onClick={() =>
                      setNetworkEnv((p) => ({ ...p, cellularAvailable: !p.cellularAvailable }))
                    }
                    className={`p-3 rounded-xl border text-left transition-colors ${
                      networkEnv.cellularAvailable
                        ? 'bg-sky-500/10 border-sky-500/40 text-slate-100'
                        : 'bg-[#0B0E14] border-slate-800 text-slate-400'
                    }`}
                  >
                    <div className="text-xs font-semibold">Telco 4G/5G Data</div>
                    <div className="text-[11px] mt-0.5 font-mono">
                      {networkEnv.cellularAvailable ? 'Available' : 'No Data Plan'}
                    </div>
                  </button>
                </div>
              </div>

              {/* Failover Dispatch Trace */}
              <div className="rounded-2xl border border-slate-800 bg-[#131822] p-5 space-y-3">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-slate-100">
                    Motion & Failover Event Trace
                  </h2>
                  {traces.length > 0 && (
                    <button
                      onClick={() => setTraces([])}
                      className="text-xs text-slate-400 hover:text-slate-200"
                    >
                      Clear
                    </button>
                  )}
                </div>

                {traces.length === 0 ? (
                  <p className="text-xs text-slate-400 py-4">
                    Tap <strong className="text-slate-200">Start Monitoring</strong> on the phone,
                    wait for the exit delay (or toggle it off), then click{' '}
                    <strong className="text-slate-200">Test Intruder</strong> or{' '}
                    <strong className="text-slate-200">Cloud Jump</strong> to observe real-time
                    detection and failover routing.
                  </p>
                ) : (
                  <div className="divide-y divide-slate-800/70">
                    {traces.map((t) => (
                      <div key={t.id} className="py-2.5 flex items-start justify-between gap-4">
                        <div className="space-y-0.5">
                          <div className="text-xs font-medium text-slate-100">{t.event}</div>
                          <div className="text-xs text-slate-400">{t.detail}</div>
                        </div>
                        <span className="text-[11px] font-mono tabular-nums text-slate-500 shrink-0">
                          {new Date(t.timestampMs).toLocaleTimeString()}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}

        {view === 'SOURCE' && (
          <AndroidSourceExplorer files={androidFiles} loading={loadingFiles} />
        )}

        {view === 'ARCHITECTURE' && (
          <ArchitectureAndBuildView
            mode="ARCHITECTURE"
            fileCount={androidFiles.length}
            onDownloadZip={handleDownloadZip}
          />
        )}

        {view === 'BUILD' && (
          <ArchitectureAndBuildView
            mode="BUILD"
            fileCount={androidFiles.length}
            onDownloadZip={handleDownloadZip}
          />
        )}
      </main>
    </div>
  );
}
