import React from 'react';
import { Download, Terminal } from 'lucide-react';

interface Props {
  mode: 'ARCHITECTURE' | 'BUILD';
  fileCount: number;
  onDownloadZip: () => void;
}

export const ArchitectureAndBuildView: React.FC<Props> = ({
  mode,
  fileCount,
  onDownloadZip,
}) => {
  if (mode === 'ARCHITECTURE') {
    return (
      <div className="space-y-8">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="rounded-2xl border border-slate-800 bg-[#131822] p-6 space-y-2">
            <div className="text-xs font-mono text-sky-400">01 · CameraX YUV_420_888</div>
            <h3 className="text-base font-semibold text-slate-100">
              Luma Motion & False-Alarm Filter
            </h3>
            <p className="text-sm text-slate-400 leading-relaxed">
              Extracts subsampled Y-plane luminance at ~3.5 FPS (280 ms throttle), detects uniform
              whole-frame exposure jumps (clouds / auto-exposure shifts) and rebases without
              alerting, requires 2 consecutive motion frames, and enforces user cooldown.
            </p>
          </div>

          <div className="rounded-2xl border border-slate-800 bg-[#131822] p-6 space-y-2">
            <div className="text-xs font-mono text-emerald-400">02 · MediaStore & Cache</div>
            <h3 className="text-base font-semibold text-slate-100">
              Dual-Resolution Capture Pipeline
            </h3>
            <p className="text-sm text-slate-400 leading-relaxed">
              Saves full-resolution timestamped JPEGs to Pictures/HomeAlert via MediaStore (API 29+
              IS_PENDING & legacy API 26–28 support) while creating a ~150–250 KB max-1280px alert
              copy for low-data cellular uploads.
            </p>
          </div>

          <div className="rounded-2xl border border-slate-800 bg-[#131822] p-6 space-y-2">
            <div className="text-xs font-mono text-amber-400">03 · 3-Tier Failover</div>
            <h3 className="text-base font-semibold text-slate-100">
              Socket-Bound Network Escalation
            </h3>
            <p className="text-sm text-slate-400 leading-relaxed">
              Verifies NET_CAPABILITY_VALIDATED on Wi-Fi with a 4s timeout, explicitly requests
              TRANSPORT_CELLULAR to bind OkHttp/SMTP sockets over 4G/5G if ISP internet is cut, and
              falls back to Dual-SIM SmsManager guarded by a 90s anti-flood floor.
            </p>
          </div>
        </div>

        {/* Failover Routing Table */}
        <div className="rounded-2xl border border-slate-800 bg-[#131822] overflow-hidden">
          <div className="px-6 py-4 border-b border-slate-800">
            <h3 className="text-sm font-semibold text-slate-100">
              Network Failover Decision Matrix
            </h3>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="border-b border-slate-800 text-slate-400">
                <tr>
                  <th className="px-6 py-3 font-medium">Mode</th>
                  <th className="px-6 py-3 font-medium">Tier 1 · Wi-Fi</th>
                  <th className="px-6 py-3 font-medium">Tier 2 · Telco Data</th>
                  <th className="px-6 py-3 font-medium">Tier 3 · Telco SMS</th>
                  <th className="px-6 py-3 font-medium">Fallback Outcome</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/70 text-slate-300">
                <tr>
                  <td className="px-6 py-3.5 font-medium text-slate-100">
                    Mode A · Wi-Fi Only
                  </td>
                  <td className="px-6 py-3.5">NET_CAPABILITY_VALIDATED (4s timeout)</td>
                  <td className="px-6 py-3.5 text-slate-500">Disabled (Never touched)</td>
                  <td className="px-6 py-3.5 text-slate-500">Disabled (Never touched)</td>
                  <td className="px-6 py-3.5">Saved Locally + Retry Queue</td>
                </tr>
                <tr>
                  <td className="px-6 py-3.5 font-medium text-slate-100">
                    Mode B · Smart Auto-Failover
                  </td>
                  <td className="px-6 py-3.5">NET_CAPABILITY_VALIDATED (4s timeout)</td>
                  <td className="px-6 py-3.5">requestNetwork(TRANSPORT_CELLULAR)</td>
                  <td className="px-6 py-3.5">SmsManager (90s anti-flood guard)</td>
                  <td className="px-6 py-3.5">Saved Locally + Retry Queue</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        {/* Unit Tests Summary */}
        <div className="rounded-2xl border border-slate-800 bg-[#131822] p-6 space-y-4">
          <h3 className="text-sm font-semibold text-slate-100">
            Included JUnit4 Unit Test Suites (app/src/test/)
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-xs">
            <div className="p-4 rounded-xl bg-[#0B0E14] border border-slate-800 space-y-1.5">
              <div className="font-mono text-sky-400">MotionDetectorTest.kt</div>
              <p className="text-slate-400">
                Validates identical frame idle state, 280 ms FPS throttling, 2-frame debounce
                requirement, global exposure jump suppression, and cooldown timer enforcement.
              </p>
            </div>
            <div className="p-4 rounded-xl bg-[#0B0E14] border border-slate-800 space-y-1.5">
              <div className="font-mono text-sky-400">NetworkFailoverEngineTest.kt</div>
              <p className="text-slate-400">
                Tests Mode A cellular/SMS isolation, Mode B Tier 1 Wi-Fi pass, Tier 2 cellular
                request + NetworkCallback release, and Tier 3 SMS fallback.
              </p>
            </div>
            <div className="p-4 rounded-xl bg-[#0B0E14] border border-slate-800 space-y-1.5">
              <div className="font-mono text-sky-400">SmsRateLimiterTest.kt</div>
              <p className="text-slate-400">
                Verifies the independent 90-second SMS anti-flood cooldown floor regardless of
                shorter snapshot cooldown slider settings.
              </p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
      <div className="lg:col-span-7 rounded-2xl border border-slate-800 bg-[#131822] p-6 space-y-5">
        <div className="space-y-1">
          <h2 className="text-lg font-semibold text-slate-100">
            Build & Sign Standalone Sideloaded APK
          </h2>
          <p className="text-sm text-slate-400">
            This web sandbox runs Node.js without an Android SDK or JDK toolchain, so the complete
            Gradle project ({fileCount} files including ProGuard rules, signing config, and unit
            tests) is packaged for immediate compilation in Android Studio or CLI.
          </p>
        </div>

        <div className="space-y-4 text-xs">
          <div className="p-4 rounded-xl bg-[#0B0E14] border border-slate-800 space-y-2">
            <div className="flex items-center gap-2 text-slate-200 font-medium">
              <Terminal className="w-4 h-4 text-sky-400" />
              1. Generate your release signing keystore
            </div>
            <pre className="font-mono text-slate-300 overflow-x-auto">{`unzip HomeAlertAndroid.zip -d HomeAlertAndroid
cd HomeAlertAndroid
chmod +x generate_keystore.sh
./generate_keystore.sh`}</pre>
          </div>

          <div className="p-4 rounded-xl bg-[#0B0E14] border border-slate-800 space-y-2">
            <div className="flex items-center gap-2 text-slate-200 font-medium">
              <Terminal className="w-4 h-4 text-sky-400" />
              2. Run Kotlin JUnit4 unit tests
            </div>
            <pre className="font-mono text-slate-300 overflow-x-auto">{`./gradlew testDebugUnitTest`}</pre>
          </div>

          <div className="p-4 rounded-xl bg-[#0B0E14] border border-slate-800 space-y-2">
            <div className="flex items-center gap-2 text-slate-200 font-medium">
              <Terminal className="w-4 h-4 text-sky-400" />
              3. Compile signed, ProGuard-obfuscated release APK
            </div>
            <pre className="font-mono text-slate-300 overflow-x-auto">{`./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk`}</pre>
          </div>
        </div>
      </div>

      <div className="lg:col-span-5 rounded-2xl border border-slate-800 bg-[#131822] p-6 space-y-4">
        <h3 className="text-sm font-semibold text-slate-100">Release Hardening Included</h3>
        <ul className="space-y-2.5 text-xs text-slate-300 leading-relaxed">
          <li>
            • <span className="font-mono text-slate-100">isMinifyEnabled = true</span> &{' '}
            <span className="font-mono text-slate-100">isShrinkResources = true</span> in{' '}
            <span className="font-mono">app/build.gradle.kts</span>
          </li>
          <li>
            • Custom <span className="font-mono text-slate-100">proguard-rules.pro</span> keeping
            CameraX Camera2Config, OkHttp, Jakarta Mail SMTP, Coroutines, and stripping debug logs
          </li>
          <li>
            • <span className="font-mono text-slate-100">hardware.telephony required="false"</span>{' '}
            in <span className="font-mono">AndroidManifest.xml</span> so SIM-less spare phones can
            install the APK
          </li>
          <li>
            • <span className="font-mono text-slate-100">foregroundServiceType="camera"</span> for
            uninterrupted overnight monitoring
          </li>
        </ul>

        <button
          onClick={onDownloadZip}
          className="w-full py-3 px-4 rounded-xl bg-sky-600 hover:bg-sky-500 text-xs font-semibold text-white flex items-center justify-center gap-2 transition-colors"
        >
          <Download className="w-4 h-4" />
          Download HomeAlertAndroid.zip
        </button>
      </div>
    </div>
  );
};
