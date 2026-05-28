import React, { useState, useEffect, useRef } from 'react';
import { Network, Database, FileText, Check, Lock, Unlock, AlertTriangle, ShieldAlert } from 'lucide-react';

export default function InteropFunnelSimulator() {
  const [allowDb, setAllowDb] = useState<boolean>(true);
  const [allowNet, setAllowNet] = useState<boolean>(false);
  const [allowFs, setAllowFs] = useState<boolean>(false);
  
  const [selectedCall, setSelectedCall] = useState<'db' | 'net' | 'fs'>('db');
  const [isRunning, setIsRunning] = useState<boolean>(false);
  
  // Animation states
  const [packetPosition, setPacketPosition] = useState<number>(0); // 0 (sandbox) to 100 (host)
  const [isReturning, setIsReturning] = useState<boolean>(false);
  const [validationStatus, setValidationStatus] = useState<'idle' | 'checking' | 'allowed' | 'blocked'>('idle');
  const [logs, setLogs] = useState<string[]>(['Ready. Select a call configuration and click Execute Bridge Call.']);

  const containerRef = useRef<HTMLDivElement>(null);
  const hasAutoRunRef = useRef<boolean>(false);

  // Intersection Observer for scroll-to-trigger auto-run (runs once)
  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !hasAutoRunRef.current && !isRunning) {
          hasAutoRunRef.current = true;
          executeCall();
        }
      },
      { threshold: 0.3 }
    );
    if (containerRef.current) {
      observer.observe(containerRef.current);
    }
    return () => observer.disconnect();
  }, [containerRef, isRunning]);

  const executeCall = () => {
    if (isRunning) return;
    setIsRunning(true);
    setIsReturning(false);
    setPacketPosition(0);
    setValidationStatus('checking');
    
    const isAllowed = 
      (selectedCall === 'db' && allowDb) ||
      (selectedCall === 'net' && allowNet) ||
      (selectedCall === 'fs' && allowFs);

    const callNames = {
      db: 'db.save("report_data")',
      net: 'http.getJson("https://api.external/data")',
      fs: 'file.read("/etc/passwd")'
    };

    setLogs([
      `[Sandbox] Nox script invoked host module call: ${callNames[selectedCall]}`,
      `[Bridge] Packet transiting sandbox boundary...`
    ]);

    // Step 1: Sandbox -> Funnel (Position 0 to 45)
    let pos = 0;
    const toFunnelInterval = setInterval(() => {
      pos += 3;
      setPacketPosition(pos);
      if (pos >= 45) {
        clearInterval(toFunnelInterval);
        
        // Step 2: Validation check inside funnel
        setLogs(prev => [...prev, `[Funnel] Scanning invocation token capabilities...`]);
        
        setTimeout(() => {
          if (isAllowed) {
            setValidationStatus('allowed');
            setLogs(prev => [
              ...prev,
              `[Funnel] Verification complete: Annotation @NoxFunction matched. Bound successfully.`,
              `[Bridge] Delivering request to Host application environment...`
            ]);
            
            // Step 3: Funnel -> Host (Position 45 to 100)
            const toHostInterval = setInterval(() => {
              pos += 5;
              setPacketPosition(pos);
              if (pos >= 95) {
                clearInterval(toHostInterval);
                setLogs(prev => [...prev, `[Host] Kotlin execution successful. Serializing return response.`]);
                
                // Step 4: Return flow Host -> Sandbox (Position 100 to 0)
                setIsReturning(true);
                const returnInterval = setInterval(() => {
                  pos -= 5;
                  setPacketPosition(pos);
                  if (pos <= 0) {
                    clearInterval(returnInterval);
                    setIsRunning(false);
                    setValidationStatus('idle');
                    setLogs(prev => [
                      ...prev,
                      `[Sandbox] Interop execution success. Return value bound to script context.`
                    ]);
                  }
                }, 25);
              }
            }, 30);

          } else {
            setValidationStatus('blocked');
            setLogs(prev => [
              ...prev,
              `[Funnel] Security Violation: Capability requested was NOT exported by Host.`,
              `[Bridge] Terminating interop call chain. Host fully isolated.`
            ]);
            
            // Rebound / Flash red then reset
            setTimeout(() => {
              setIsRunning(false);
              setValidationStatus('idle');
              setPacketPosition(0);
            }, 2500);
          }
        }, 1000);
      }
    }, 25);
  };

  return (
    <div 
      ref={containerRef}
      className="w-full bg-[#09090b] rounded-2xl border border-zinc-800/80 p-6 md:p-8 flex flex-col gap-6 md:gap-8 shadow-2xl relative overflow-hidden group"
    >
      {/* Background radial gradient */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_800px_at_50%_100%,rgba(6,182,212,0.02),transparent)] pointer-events-none" />

      {/* Header Info */}
      <div className="flex justify-between items-start gap-4">
        <div>
          <span className="text-[10px] font-bold tracking-widest text-cyan-400 uppercase bg-cyan-950/40 border border-cyan-800/50 px-2 py-0.5 rounded">
            Kotlin Interop
          </span>
          <h3 className="text-xl md:text-2xl font-bold text-white mt-2">Secure Interop Bridge Funnel</h3>
        </div>
        <div className="flex items-center gap-1.5 bg-zinc-900 border border-zinc-800 px-3 py-1.5 rounded-lg">
          <Unlock className="w-4 h-4 text-cyan-400" />
          <span className="text-xs font-mono font-medium text-zinc-300">
            Boundary: <span className="text-cyan-400 font-semibold">Strict Filtering</span>
          </span>
        </div>
      </div>

      <div className="grid lg:grid-cols-5 gap-6 items-stretch">
        {/* Panel 1: Host Application Controls */}
        <div className="lg:col-span-2 bg-black/40 border border-zinc-800/60 rounded-xl p-4 flex flex-col gap-4">
          <div>
            <h4 className="text-xs font-bold text-zinc-400 uppercase tracking-wider mb-3">
              1. Host Privileges (Kotlin)
            </h4>
            <p className="text-xs text-zinc-500 mb-4 leading-normal">
              Toggle which local JVM resources the embedding application registers into the Nox VM context:
            </p>

            <div className="flex flex-col gap-2.5">
              {/* Database toggle */}
              <label className="flex items-center justify-between p-3 bg-zinc-950 border border-zinc-850 hover:border-zinc-800 rounded-lg cursor-pointer transition-colors">
                <span className="flex items-center gap-2 text-xs font-semibold text-zinc-300">
                  <Database className={`w-4 h-4 ${allowDb ? 'text-cyan-400' : 'text-zinc-650'}`} />
                  Export Database APIs
                </span>
                <input 
                  type="checkbox" 
                  checked={allowDb} 
                  disabled={isRunning}
                  onChange={(e) => setAllowDb(e.target.checked)}
                  className="w-4 h-4 rounded border-zinc-700 bg-zinc-900 text-cyan-500 focus:ring-cyan-500 accent-cyan-500 disabled:opacity-50"
                />
              </label>

              {/* Http toggle */}
              <label className="flex items-center justify-between p-3 bg-zinc-950 border border-zinc-850 hover:border-zinc-800 rounded-lg cursor-pointer transition-colors">
                <span className="flex items-center gap-2 text-xs font-semibold text-zinc-300">
                  <Network className={`w-4 h-4 ${allowNet ? 'text-cyan-400' : 'text-zinc-650'}`} />
                  Export Http Client APIs
                </span>
                <input 
                  type="checkbox" 
                  checked={allowNet} 
                  disabled={isRunning}
                  onChange={(e) => setAllowNet(e.target.checked)}
                  className="w-4 h-4 rounded border-zinc-700 bg-zinc-900 text-cyan-500 focus:ring-cyan-500 accent-cyan-500 disabled:opacity-50"
                />
              </label>

              {/* Filesystem toggle */}
              <label className="flex items-center justify-between p-3 bg-zinc-950 border border-zinc-850 hover:border-zinc-800 rounded-lg cursor-pointer transition-colors">
                <span className="flex items-center gap-2 text-xs font-semibold text-zinc-300">
                  <FileText className={`w-4 h-4 ${allowFs ? 'text-cyan-400' : 'text-zinc-650'}`} />
                  Export File System APIs
                </span>
                <input 
                  type="checkbox" 
                  checked={allowFs} 
                  disabled={isRunning}
                  onChange={(e) => setAllowFs(e.target.checked)}
                  className="w-4 h-4 rounded border-zinc-700 bg-zinc-900 text-cyan-500 focus:ring-cyan-500 accent-cyan-500 disabled:opacity-50"
                />
              </label>
            </div>
          </div>

          <div className="mt-auto border-t border-zinc-850 pt-4 flex flex-col gap-3">
            <h4 className="text-xs font-bold text-zinc-400 uppercase tracking-wider">
              2. Sandbox Interop Script
            </h4>
            <div className="flex gap-1.5 flex-wrap">
              <button
                onClick={() => setSelectedCall('db')}
                disabled={isRunning}
                className={`py-1.5 px-2.5 text-[10px] font-bold font-mono rounded border transition-all ${
                  selectedCall === 'db' 
                    ? 'bg-cyan-950/30 text-cyan-400 border-cyan-900/60' 
                    : 'bg-zinc-950 text-zinc-500 border-zinc-850 hover:text-zinc-450 hover:border-zinc-800'
                } disabled:opacity-50`}
              >
                db.save()
              </button>
              <button
                onClick={() => setSelectedCall('net')}
                disabled={isRunning}
                className={`py-1.5 px-2.5 text-[10px] font-bold font-mono rounded border transition-all ${
                  selectedCall === 'net' 
                    ? 'bg-cyan-950/30 text-cyan-400 border-cyan-900/60' 
                    : 'bg-zinc-950 text-zinc-500 border-zinc-850 hover:text-zinc-450 hover:border-zinc-800'
                } disabled:opacity-50`}
              >
                http.getJson()
              </button>
              <button
                onClick={() => setSelectedCall('fs')}
                disabled={isRunning}
                className={`py-1.5 px-2.5 text-[10px] font-bold font-mono rounded border transition-all ${
                  selectedCall === 'fs' 
                    ? 'bg-cyan-950/30 text-cyan-400 border-cyan-900/60' 
                    : 'bg-zinc-950 text-zinc-500 border-zinc-850 hover:text-zinc-450 hover:border-zinc-800'
                } disabled:opacity-50`}
              >
                file.read()
              </button>
            </div>

            <button
              onClick={executeCall}
              disabled={isRunning}
              className="py-2.5 px-4 rounded-xl bg-white text-black font-bold text-sm hover:bg-zinc-200 transition-colors flex items-center justify-center gap-2 shadow-lg disabled:opacity-50 disabled:cursor-not-allowed mt-1 cursor-pointer"
            >
              Execute Bridge Call
            </button>
          </div>
        </div>

        {/* Panel 2: Secure Bridge Funnel Animation Display */}
        <div className="lg:col-span-3 bg-zinc-950/40 border border-zinc-850 p-4 md:p-6 rounded-xl flex flex-col justify-center relative min-h-[300px]">
          {/* Visualizing Host on the Left, Funnel in center, Sandbox on right */}
          <div className="w-full flex items-center justify-between relative h-48 border border-zinc-850/60 bg-black/30 rounded-xl p-4 overflow-hidden">
            
            {/* 1. Host side (Left) */}
            <div className="w-20 flex flex-col gap-2 items-center text-center z-10">
              <span className="text-[9px] font-bold font-mono tracking-widest text-zinc-550 uppercase">HOST ENV</span>
              
              <div className="flex flex-col gap-1 w-full">
                {/* Visual indicator boxes for DB, Net, Fs */}
                <div className={`p-1.5 border rounded-lg flex items-center justify-center transition-all ${
                  allowDb && validationStatus === 'allowed' && selectedCall === 'db'
                    ? 'bg-emerald-950/30 border-emerald-500/80 shadow-[0_0_12px_rgba(16,185,129,0.3)]'
                    : allowDb 
                      ? 'bg-zinc-950 border-zinc-800 text-zinc-450' 
                      : 'bg-zinc-900 border-zinc-850 opacity-40 text-zinc-700'
                }`}>
                  <Database className="w-3.5 h-3.5" />
                </div>

                <div className={`p-1.5 border rounded-lg flex items-center justify-center transition-all ${
                  allowNet && validationStatus === 'allowed' && selectedCall === 'net'
                    ? 'bg-emerald-950/30 border-emerald-500/80 shadow-[0_0_12px_rgba(16,185,129,0.3)]'
                    : allowNet 
                      ? 'bg-zinc-950 border-zinc-800 text-zinc-450' 
                      : 'bg-zinc-900 border-zinc-850 opacity-40 text-zinc-700'
                }`}>
                  <Network className="w-3.5 h-3.5" />
                </div>

                <div className={`p-1.5 border rounded-lg flex items-center justify-center transition-all ${
                  allowFs && validationStatus === 'allowed' && selectedCall === 'fs'
                    ? 'bg-emerald-950/30 border-emerald-500/80 shadow-[0_0_12px_rgba(16,185,129,0.3)]'
                    : allowFs 
                      ? 'bg-zinc-950 border-zinc-800 text-zinc-450' 
                      : 'bg-zinc-900 border-zinc-850 opacity-40 text-zinc-700'
                }`}>
                  <FileText className="w-3.5 h-3.5" />
                </div>
              </div>
            </div>

            {/* 2. Funnel Secure Bridge (Center) */}
            <div className="absolute left-[38%] right-[38%] top-6 bottom-6 border border-zinc-850/80 bg-zinc-950/80 rounded-2xl flex flex-col items-center justify-center p-2 z-10 shadow-[inset_0_2px_10px_rgba(0,0,0,0.8)]">
              {/* Visualizing the "funnel boundary filter" */}
              <div className="text-[8px] font-bold font-mono tracking-widest text-zinc-500 uppercase text-center mb-1.5">
                SECURE BRIDGE
              </div>

              {/* The mechanical shape funnel */}
              <div className={`w-12 h-16 relative flex items-center justify-center border-t-2 border-b-2 rounded-lg transition-all duration-300 ${
                validationStatus === 'allowed'
                  ? 'bg-emerald-950/15 border-emerald-500/60 shadow-[0_0_15px_rgba(16,185,129,0.25)]'
                  : validationStatus === 'blocked'
                    ? 'bg-red-950/20 border-red-500/80 shadow-[0_0_20px_rgba(239,68,68,0.3)]'
                    : 'bg-zinc-900 border-zinc-800'
              }`}>
                {/* Laser scan lines inside funnel */}
                {validationStatus === 'checking' && (
                  <div className="absolute inset-x-0 h-0.5 bg-cyan-400 shadow-[0_0_8px_rgba(34,211,238,0.8)] animate-[bounce_1.5s_infinite]" />
                )}

                {validationStatus === 'allowed' && <Check className="w-6 h-6 text-emerald-400 animate-pulse" />}
                {validationStatus === 'blocked' && <Lock className="w-6 h-6 text-red-500 animate-[shake_0.4s_infinite]" />}
                {validationStatus === 'idle' && <Unlock className="w-4 h-4 text-zinc-600" />}
              </div>
              
              <span className={`text-[7px] font-bold font-mono mt-1 ${
                validationStatus === 'allowed' ? 'text-emerald-400' : validationStatus === 'blocked' ? 'text-red-400' : 'text-zinc-650'
              }`}>
                {validationStatus === 'checking' ? 'VALIDATING...' : validationStatus === 'allowed' ? 'VALIDATED' : validationStatus === 'blocked' ? 'REJECTED' : 'STANDBY'}
              </span>
            </div>

            {/* 3. Sandbox side (Right) */}
            <div className="w-20 flex flex-col gap-3 items-center text-center z-10">
              <span className="text-[9px] font-bold font-mono tracking-widest text-cyan-500 uppercase">VM SANDBOX</span>
              <div className="w-12 h-12 rounded-full border border-cyan-800 bg-cyan-950/15 flex items-center justify-center shadow-[inset_0_0_10px_rgba(6,182,212,0.2)]">
                <span className="font-mono text-[9px] font-semibold text-cyan-400 uppercase tracking-wide">SCRIPT</span>
              </div>
            </div>

            {/* Connecting visual pipes */}
            <div className="absolute inset-x-12 top-[50%] h-[3px] bg-zinc-900 border-t border-zinc-800 pointer-events-none" />

            {/* Floating Active Particle Packet along transit line */}
            {isRunning && (
              <div
                className={`absolute w-3.5 h-3.5 rounded-full transition-all duration-75 ease-linear pointer-events-none z-20 ${
                  isReturning
                    ? 'bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.8)]'
                    : validationStatus === 'blocked'
                      ? 'bg-red-500 shadow-[0_0_10px_rgba(239,68,68,0.8)]'
                      : 'bg-cyan-400 shadow-[0_0_8px_rgba(34,211,238,0.8)]'
                }`}
                style={{
                  left: `calc(${100 - packetPosition}% - 7px)`,
                  top: 'calc(50% - 7px)',
                  opacity: packetPosition > 0 ? 1 : 0
                }}
              />
            )}
          </div>

          {/* Real-time Logger Terminal for logs */}
          <div className="bg-[#020202] rounded-xl border border-zinc-850 p-4 font-mono text-[11px] leading-relaxed text-zinc-400 min-h-[110px] mt-4 flex flex-col justify-start overflow-y-auto">
            {logs.map((log, index) => {
              const isCrit = log.includes('Security Violation') || log.includes('Terminating');
              const isSucc = log.includes('Verification complete') || log.includes('success') || log.includes('context');
              return (
                <div 
                  key={index} 
                  className={`animate-fade-in flex items-center gap-1.5 ${
                    isCrit ? 'text-red-400 font-medium' : isSucc ? 'text-cyan-400' : 'text-zinc-500'
                  }`}
                >
                  {isCrit && <ShieldAlert className="w-3.5 h-3.5 text-red-500 shrink-0" />}
                  {isSucc && <Check className="w-3.5 h-3.5 text-cyan-400 shrink-0" />}
                  <span>{log}</span>
                </div>
              );
            })}
          </div>
        </div>
      </div>
      
      <style>{`
        @keyframes shake {
          0%, 100% { transform: translate(0, 0); }
          25% { transform: translate(-1px, 1px); }
          75% { transform: translate(1px, -1px); }
        }
      `}</style>
    </div>
  );
}
