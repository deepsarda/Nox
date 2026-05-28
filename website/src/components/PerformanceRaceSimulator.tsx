import React, { useState, useEffect, useRef } from 'react';
import { Zap, Play, RotateCcw, AlertOctagon, CheckCircle2, Rocket } from 'lucide-react';

interface Packets {
  id: number;
  progress: number; // 0 to 100
  paused: boolean;
}

export default function PerformanceRaceSimulator() {
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [traditionalPackets, setTraditionalPackets] = useState<Packets[]>([]);
  const [noxPackets, setNoxPackets] = useState<Packets[]>([]);
  const [traditionalGcActive, setTraditionalGcActive] = useState<boolean>(false);
  
  // Real-time metric counters
  const [traditionalLatency, setTraditionalLatency] = useState<number>(0);
  const [noxLatency, setNoxLatency] = useState<number>(0);
  const [traditionalGcPauses, setTraditionalGcPauses] = useState<number>(0);
  const [traditionalOps, setTraditionalOps] = useState<number>(0);
  const [noxOps, setNoxOps] = useState<number>(0);
  const [raceFinished, setRaceFinished] = useState<boolean>(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const hasAutoRunRef = useRef<boolean>(false);

  // Initialize packets
  useEffect(() => {
    resetRace();
  }, []);

  // Intersection Observer for scroll-to-trigger auto-run (runs once)
  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !hasAutoRunRef.current && !isRunning && !raceFinished) {
          hasAutoRunRef.current = true;
          startRace();
        }
      },
      { threshold: 0.3 }
    );
    if (containerRef.current) {
      observer.observe(containerRef.current);
    }
    return () => observer.disconnect();
  }, [containerRef, isRunning, raceFinished]);

  // GC Interval Sweep Simulator
  useEffect(() => {
    if (!isRunning || raceFinished) return;

    const gcInterval = setInterval(() => {
      // Trigger a stop-the-world sweep on traditional track
      setTraditionalGcActive(true);
      setTraditionalGcPauses(prev => prev + 1);
      
      // Traditional ops drop to zero
      setTraditionalOps(0);

      // Freeze all traditional packets
      setTraditionalPackets(prev => prev.map(p => ({ ...p, paused: true })));

      // GC sweep duration: 1000ms
      setTimeout(() => {
        setTraditionalGcActive(false);
        setTraditionalPackets(prev => prev.map(p => ({ ...p, paused: false })));
      }, 1000);

    }, 3000); // Triggers GC sweep every 3 seconds

    return () => clearInterval(gcInterval);
  }, [isRunning, raceFinished]);

  // Animation Loop
  useEffect(() => {
    if (!isRunning || raceFinished) return;

    let traditionalFinishedCount = 0;
    let noxFinishedCount = 0;
    const totalCount = 30; // Total packets to finish the race
    
    // Spawning interval
    const spawnInterval = setInterval(() => {
      // Spawn new packet if not finished
      if (traditionalPackets.length < totalCount) {
        setTraditionalPackets(prev => [
          ...prev, 
          { id: Date.now() + Math.random(), progress: 0, paused: false }
        ]);
      }
      if (noxPackets.length < totalCount) {
        setNoxPackets(prev => [
          ...prev, 
          { id: Date.now() + Math.random() + 1, progress: 0, paused: false }
        ]);
      }
    }, 200);

    // Physics update interval
    const updateInterval = setInterval(() => {
      // Update traditional packets (slower speed, blocks when GC is active)
      setTraditionalPackets(prev => {
        const next = prev.map(p => {
          if (p.paused || traditionalGcActive) return p;
          const nextProgress = p.progress + 1.2; // Slower basic speed
          if (nextProgress >= 100) {
            traditionalFinishedCount++;
          }
          return { ...p, progress: Math.min(nextProgress, 100) };
        });
        return next;
      });

      // Update Nox packets (2.5x speed, never freezes)
      setNoxPackets(prev => {
        const next = prev.map(p => {
          const nextProgress = p.progress + 3.0; // Fast speed
          if (nextProgress >= 100) {
            noxFinishedCount++;
          }
          return { ...p, progress: Math.min(nextProgress, 100) };
        });
        return next;
      });

      // Update Latency / Timers
      setNoxLatency(prev => {
        // Nox finishes faster
        const countFinished = noxPackets.filter(p => p.progress >= 100).length;
        if (countFinished >= totalCount) return prev;
        return prev + 16; // increment approx ms
      });

      setTraditionalLatency(prev => {
        const countFinished = traditionalPackets.filter(p => p.progress >= 100).length;
        if (countFinished >= totalCount) return prev;
        return prev + 16;
      });

      // Update active Ops/sec speeds (fluctuates slightly for realism)
      if (!traditionalGcActive) {
        setTraditionalOps(Math.floor(2.1 + (Math.random() - 0.5) * 0.4));
      }
      setNoxOps(Math.floor(15.4 + (Math.random() - 0.5) * 0.8));

      // End of Race conditions
      const tradDone = traditionalPackets.length >= totalCount && traditionalPackets.every(p => p.progress >= 100);
      const noxDone = noxPackets.length >= totalCount && noxPackets.every(p => p.progress >= 100);
      
      if (tradDone && noxDone) {
        setRaceFinished(true);
        setIsRunning(false);
      }
    }, 16); // 60 FPS

    return () => {
      clearInterval(spawnInterval);
      clearInterval(updateInterval);
    };
  }, [isRunning, traditionalGcActive, traditionalPackets.length, noxPackets.length, raceFinished]);

  const startRace = () => {
    if (isRunning) return;
    setIsRunning(true);
    setRaceFinished(false);
    setTraditionalLatency(0);
    setNoxLatency(0);
    setTraditionalGcPauses(0);
    setTraditionalPackets([]);
    setNoxPackets([]);
  };

  const resetRace = () => {
    setIsRunning(false);
    setRaceFinished(false);
    setTraditionalLatency(0);
    setNoxLatency(0);
    setTraditionalGcPauses(0);
    setTraditionalOps(0);
    setNoxOps(0);
    setTraditionalGcActive(false);

    // Initial passive packet lists
    setTraditionalPackets(Array.from({ length: 5 }, (_, i) => ({
      id: i,
      progress: i * 15,
      paused: false
    })));
    setNoxPackets(Array.from({ length: 8 }, (_, i) => ({
      id: i,
      progress: i * 12,
      paused: false
    })));
  };

  return (
    <div 
      ref={containerRef}
      className="w-full bg-[#09090b] rounded-2xl border border-zinc-800/80 p-6 md:p-8 flex flex-col gap-6 md:gap-8 shadow-2xl relative overflow-hidden group"
    >
      {/* Background radial gradient */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_800px_at_0%_0%,rgba(6,182,212,0.03),transparent)] pointer-events-none" />

      {/* Header */}
      <div className="flex justify-between items-start gap-4">
        <div>
          <span className="text-[10px] font-bold tracking-widest text-cyan-400 uppercase bg-cyan-950/40 border border-cyan-800/50 px-2 py-0.5 rounded">
            Blazing Fast
          </span>
          <h3 className="text-xl md:text-2xl font-bold text-white mt-2">Zero-GC Primitives Race</h3>
        </div>
        <div className="flex items-center gap-1.5 bg-zinc-900 border border-zinc-800 px-3 py-1.5 rounded-lg">
          <Zap className="w-4 h-4 text-cyan-400" />
          <span className="text-xs font-mono font-medium text-zinc-300">
            Nox VM: <span className="text-cyan-400 font-semibold">Microsecond Boot</span>
          </span>
        </div>
      </div>

      <div className="grid lg:grid-cols-4 gap-6 items-center">
        {/* Left Side: Stats and Control Panel */}
        <div className="lg:col-span-1 flex flex-col gap-4">
          <div className="bg-black/40 border border-zinc-800/60 rounded-xl p-4 flex flex-col gap-3">
            <h4 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">
              VM Benchmarks
            </h4>

            {/* Nox Stats Panel */}
            <div className="border-l-2 border-cyan-500 pl-3 py-1 bg-cyan-950/5">
              <div className="text-[10px] uppercase font-bold tracking-wider text-cyan-400">Nox VM</div>
              <div className="text-lg font-mono font-bold text-white leading-none mt-1">
                {(noxLatency / 1000).toFixed(2)}s
              </div>
              <div className="text-[10px] text-zinc-500 font-mono mt-0.5">
                Latency • {noxOps}M ops/s
              </div>
              <div className="text-[10px] font-semibold text-emerald-400 font-mono mt-0.5">
                0 Primitive GC Pauses
              </div>
            </div>

            {/* Traditional VM Stats Panel */}
            <div className="border-l-2 border-red-500 pl-3 py-1 bg-red-950/5">
              <div className="text-[10px] uppercase font-bold tracking-wider text-red-400">Traditional VM</div>
              <div className="text-lg font-mono font-bold text-white leading-none mt-1">
                {(traditionalLatency / 1000).toFixed(2)}s
              </div>
              <div className="text-[10px] text-zinc-500 font-mono mt-0.5">
                Latency • {traditionalOps}M ops/s
              </div>
              <div className="text-[10px] font-semibold text-red-400 font-mono mt-0.5 flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse" />
                {traditionalGcPauses} GC Pauses
              </div>
            </div>
          </div>

          <div className="flex gap-2">
            <button
              onClick={startRace}
              disabled={isRunning}
              className="flex-grow py-2.5 px-4 rounded-xl bg-white text-black font-bold text-sm hover:bg-zinc-200 transition-colors flex items-center justify-center gap-2 shadow-lg disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
            >
              <Play className="w-4 h-4 fill-current" />
              {isRunning ? 'Racing...' : raceFinished ? 'Restart' : 'Start Race'}
            </button>
            <button
              onClick={resetRace}
              className="p-2.5 rounded-xl bg-zinc-900 text-zinc-400 border border-zinc-800 hover:bg-zinc-800 hover:text-white transition-colors cursor-pointer"
              title="Reset"
            >
              <RotateCcw className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Right Side: The Race Tracks */}
        <div className="lg:col-span-3 flex flex-col gap-6 bg-zinc-950/40 border border-zinc-850 p-4 md:p-6 rounded-xl relative min-h-[220px] justify-center">
          {/* TRACK 1: NOX VM */}
          <div className="flex flex-col gap-2 relative">
            <div className="flex justify-between items-center text-xs">
              <span className="font-bold text-white flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-cyan-500 shadow-[0_0_8px_rgba(6,182,212,0.8)]" />
                Nox VM (Pre-allocated Primitives, Zero-GC pMem)
              </span>
              <span className="text-[10px] font-mono text-cyan-400 uppercase tracking-widest bg-cyan-950/40 border border-cyan-900/40 px-2 py-0.5 rounded flex items-center gap-1">
                <Rocket className="w-3 h-3 text-cyan-400" />
                Pipelined
              </span>
            </div>

            {/* Track rail */}
            <div className="h-10 bg-black/60 rounded-lg border border-zinc-800/80 relative overflow-hidden flex items-center shadow-[inset_0_2px_8px_rgba(0,0,0,0.8)]">
              {/* Finish line checkerboard pattern at the right */}
              <div className="absolute right-0 top-0 bottom-0 w-4 bg-[linear-gradient(45deg,#27272a_25%,transparent_25%),linear-gradient(-45deg,#27272a_25%,transparent_25%),linear-gradient(45deg,transparent_75%,#27272a_75%),linear-gradient(-45deg,transparent_75%,#27272a_75%)] bg-[size:8px_8px] border-l border-zinc-800" />

              {/* Running packets */}
              {noxPackets.map(p => (
                <div
                  key={p.id}
                  className="absolute w-3.5 h-3.5 rounded-full bg-cyan-400 shadow-[0_0_10px_rgba(34,211,238,0.8)] transition-all duration-75 ease-linear"
                  style={{
                    left: `calc(${p.progress}% - 8px)`,
                    opacity: p.progress >= 100 ? 0 : 1,
                    transform: `scale(${p.progress >= 100 ? 0 : 1})`
                  }}
                />
              ))}

              {/* Grid track marks for sci-fi look */}
              <div className="absolute inset-x-0 inset-y-0 bg-[linear-gradient(to_right,rgba(255,255,255,0.015)_1px,transparent_1px)] bg-[size:40px_100%] pointer-events-none" />
            </div>
          </div>

          {/* TRACK 2: TRADITIONAL VM */}
          <div className="flex flex-col gap-2 relative">
            <div className="flex justify-between items-center text-xs">
              <span className="font-bold text-zinc-400 flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-red-500 shadow-[0_0_8px_rgba(239,68,68,0.8)] animate-pulse" />
                Dynamic VM (Stop-the-World GC Sweeps)
              </span>
              {traditionalGcActive ? (
                <span className="text-[10px] font-bold font-mono text-red-400 uppercase tracking-widest bg-red-950/40 border border-red-900/40 px-2 py-0.5 rounded flex items-center gap-1 animate-pulse">
                  <AlertOctagon className="w-3 h-3 text-red-500" />
                  GC PAUSE ACTIVE
                </span>
              ) : (
                <span className="text-[10px] font-mono text-zinc-500 uppercase tracking-widest bg-zinc-900 border border-zinc-800 px-2 py-0.5 rounded">
                  Sweeping Soon
                </span>
              )}
            </div>

            {/* Track rail */}
            <div className="h-10 bg-black/60 rounded-lg border border-zinc-800/80 relative overflow-hidden flex items-center shadow-[inset_0_2px_8px_rgba(0,0,0,0.8)]">
              {/* Finish line checkerboard pattern */}
              <div className="absolute right-0 top-0 bottom-0 w-4 bg-[linear-gradient(45deg,#27272a_25%,transparent_25%),linear-gradient(-45deg,#27272a_25%,transparent_25%),linear-gradient(45deg,transparent_75%,#27272a_75%),linear-gradient(-45deg,transparent_75%,#27272a_75%)] bg-[size:8px_8px] border-l border-zinc-800" />

              {/* Running packets */}
              {traditionalPackets.map(p => (
                <div
                  key={p.id}
                  className={`absolute w-3.5 h-3.5 rounded-full bg-red-500 shadow-[0_0_8px_rgba(239,68,68,0.6)] transition-all duration-75 ease-linear ${
                    p.paused || traditionalGcActive ? 'animate-[shake_0.5s_infinite]' : ''
                  }`}
                  style={{
                    left: `calc(${p.progress}% - 8px)`,
                    opacity: p.progress >= 100 ? 0 : 1,
                    transform: `scale(${p.progress >= 100 ? 0 : 1})`
                  }}
                />
              ))}

              {/* The "Stop-the-World GC" physical wall/gate */}
              <div className={`absolute top-0 bottom-0 w-2.5 bg-red-950 border-x border-red-500/80 shadow-[0_0_15px_rgba(239,68,68,0.4)] transition-all duration-300 pointer-events-none flex items-center justify-center`}
                style={{
                  left: '60%',
                  transform: traditionalGcActive ? 'translateY(0%)' : 'translateY(-110%)',
                  opacity: traditionalGcActive ? 1 : 0
                }}
              >
                <div className="w-1 h-6 bg-red-500 animate-pulse" />
              </div>

              {/* Grid track marks */}
              <div className="absolute inset-x-0 inset-y-0 bg-[linear-gradient(to_right,rgba(255,255,255,0.015)_1px,transparent_1px)] bg-[size:40px_100%] pointer-events-none" />
            </div>
          </div>
        </div>
      </div>

      {/* Narrative Explanation */}
      <div className="bg-[#020202] rounded-xl border border-zinc-850 p-4 font-mono text-[11px] md:text-xs leading-relaxed text-zinc-400 flex justify-between items-center gap-4 flex-wrap">
        {!isRunning && !raceFinished ? (
          <div className="text-zinc-500 flex items-center gap-1.5">
            <span className="text-cyan-600 font-bold">benchmark:</span> Click Start Race to observe primitive execution flow and Stop-The-World GC sweeps side-by-side.
          </div>
        ) : isRunning ? (
          <div className="text-zinc-300 flex items-center gap-1.5">
            <span className="text-cyan-600 font-bold">running:</span> Nox processes primitive registers (`pMem`) with zero boxing overhead. Traditional VMs trigger GC sweeps to box numeric variables.
          </div>
        ) : (
          <div className="text-emerald-400 font-medium flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 text-cyan-400 shrink-0" />
            <span>Nox VM finished {(traditionalLatency / noxLatency).toFixed(1)}x faster. Low-latency primitive registers and compiler-directed KILL_REF cleanups!</span>
          </div>
        )}
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
