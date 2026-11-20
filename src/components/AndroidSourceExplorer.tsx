import React, { useMemo, useState } from 'react';
import { Check, Copy, FileCode, Search } from 'lucide-react';
import { AndroidFileEntry } from '../types';

interface Props {
  files: AndroidFileEntry[];
  loading: boolean;
}

export const AndroidSourceExplorer: React.FC<Props> = ({ files, loading }) => {
  const [selectedPath, setSelectedPath] = useState<string>(
    'app/src/main/java/com/homealert/android/engine/MotionDetector.kt'
  );
  const [query, setQuery] = useState<string>('');
  const [copied, setCopied] = useState<boolean>(false);

  const filteredFiles = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return files;
    return files.filter((f) => f.path.toLowerCase().includes(q));
  }, [files, query]);

  const activeFile = useMemo(() => {
    return (
      files.find((f) => f.path === selectedPath) ||
      filteredFiles[0] ||
      files[0] ||
      null
    );
  }, [files, filteredFiles, selectedPath]);

  const handleCopy = async () => {
    if (!activeFile) return;
    await navigator.clipboard.writeText(activeFile.content);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1800);
  };

  if (loading) {
    return (
      <div className="rounded-2xl border border-slate-800 bg-[#131822] p-12 text-center text-sm text-slate-400">
        Loading Android Studio project tree...
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-stretch">
      {/* File Tree Sidebar */}
      <div className="lg:col-span-4 rounded-2xl border border-slate-800 bg-[#131822] flex flex-col overflow-hidden max-h-[760px]">
        <div className="p-4 border-b border-slate-800 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-slate-100">
              HomeAlertAndroid Files
            </h2>
            <span className="text-xs font-mono tabular-nums text-slate-400">
              {files.length} files
            </span>
          </div>
          <div className="relative">
            <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              placeholder="Filter Kotlin, Gradle, ProGuard..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="w-full pl-9 pr-3 py-2 rounded-lg bg-[#0B0E14] border border-slate-800 text-xs text-slate-100 focus:outline-none focus:border-sky-500"
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto divide-y divide-slate-800/50">
          {filteredFiles.map((file) => {
            const active = activeFile?.path === file.path;
            return (
              <button
                key={file.path}
                onClick={() => setSelectedPath(file.path)}
                className={`w-full text-left px-4 py-2.5 flex items-center justify-between gap-2 transition-colors ${
                  active
                    ? 'bg-sky-500/15 text-sky-300'
                    : 'text-slate-300 hover:bg-slate-800/40'
                }`}
              >
                <div className="flex items-center gap-2.5 min-w-0">
                  <FileCode className="w-3.5 h-3.5 shrink-0 opacity-75" />
                  <span className="text-xs font-mono truncate">{file.path}</span>
                </div>
                <span className="text-[11px] font-mono tabular-nums text-slate-500 shrink-0">
                  {(file.size / 1024).toFixed(1)} KB
                </span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Code Viewer */}
      <div className="lg:col-span-8 rounded-2xl border border-slate-800 bg-[#131822] flex flex-col overflow-hidden max-h-[760px]">
        {activeFile ? (
          <>
            <div className="px-5 py-3.5 border-b border-slate-800 flex items-center justify-between gap-4">
              <div className="min-w-0">
                <div className="text-xs font-mono text-slate-100 truncate">
                  {activeFile.path}
                </div>
                <div className="text-[11px] text-slate-400 font-mono tabular-nums mt-0.5">
                  {activeFile.language} · {activeFile.content.split('\n').length} lines ·{' '}
                  {activeFile.size} bytes
                </div>
              </div>
              <button
                onClick={handleCopy}
                className="px-3 py-1.5 rounded-lg bg-[#0B0E14] border border-slate-700 text-xs font-medium text-slate-200 hover:border-sky-500 flex items-center gap-1.5 shrink-0 whitespace-nowrap"
              >
                {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                {copied ? 'Copied' : 'Copy File'}
              </button>
            </div>
            <pre className="flex-1 overflow-auto p-5 text-xs font-mono leading-relaxed text-slate-200 bg-[#0B0E14]">
              <code>{activeFile.content}</code>
            </pre>
          </>
        ) : (
          <div className="p-12 text-center text-sm text-slate-400">
            Select a file to inspect its source code.
          </div>
        )}
      </div>
    </div>
  );
};
