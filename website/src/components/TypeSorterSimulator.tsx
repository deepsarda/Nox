import React, { useState, useEffect, useRef } from 'react';
import { ShieldCheck, Play, RotateCcw, AlertCircle, Sparkles, Check, FileCode, CheckCircle2 } from 'lucide-react';

interface SorterBlock {
  id: string;
  name: string;
  value: string | number;
  actualType: 'int' | 'boolean' | 'string';
  expectedType: 'int' | 'boolean';
  progress: number; // 0 to 100 on belt
  status: 'pending' | 'scanning' | 'passed' | 'failed' | 'fixed';
}

export default function TypeSorterSimulator() {
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [isErrorState, setIsErrorState] = useState<boolean>(false);
  const [isCompiled, setIsCompiled] = useState<boolean>(false);
  const [hoveredError, setHoveredError] = useState<boolean>(false);
  
  // Sequential blocks on a single conveyor belt lane
  const [blocks, setBlocks] = useState<SorterBlock[]>([]);
  
  const containerRef = useRef<HTMLDivElement>(null);
  const hasAutoRunRef = useRef<boolean>(false);

  useEffect(() => {
    resetSorter();
  }, []);

  // Intersection Observer for scroll-to-trigger auto-run (runs once)
  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !hasAutoRunRef.current && !isRunning && !isCompiled) {
          hasAutoRunRef.current = true;
          startScan();
        }
      },
      { threshold: 0.3 }
    );
    if (containerRef.current) {
      observer.observe(containerRef.current);
    }
    return () => observer.disconnect();
  }, [containerRef, isRunning, isCompiled]);

  // Simulation state update loop
  useEffect(() => {
    if (!isRunning || isErrorState || isCompiled) return;

    const interval = setInterval(() => {
      setBlocks(prev => {
        let errorTriggered = false;
        let compilationFinished = false;

        const updated = prev.map(block => {
          if (block.status === 'passed') {
            // Already passed block continues into the sandbox (progress 50 to 100)
            return { ...block, progress: Math.min(block.progress + 1.5, 100) };
          }
          if (block.status === 'failed') {
            return block;
          }

          // Move pending blocks forward
          let nextProgress = block.progress + 1.5;
          let status = block.status;

          // The scanner gate is located at progress = 50%
          if (nextProgress >= 47 && block.progress < 47) {
            status = 'scanning';
          }

          if (nextProgress >= 50 && block.progress < 50) {
            if (block.actualType === block.expectedType) {
              status = 'passed';
              nextProgress = 50; // Pause briefly at gate, then proceed next ticks
            } else {
              status = 'failed';
              nextProgress = 50;
              errorTriggered = true;
            }
          }

          return { ...block, progress: nextProgress, status };
        });

        // Handle error pause
        if (errorTriggered) {
          setIsErrorState(true);
          setIsRunning(false);
          clearInterval(interval);
        }

        // Check if all blocks have passed and reached sandbox
        if (updated.every(b => b.status === 'passed' && b.progress >= 95)) {
          compilationFinished = true;
          setIsCompiled(true);
          setIsRunning(false);
          clearInterval(interval);
        }

        return updated;
      });
    }, 30);

    return () => clearInterval(interval);
  }, [isRunning, isErrorState, isCompiled]);

  const startScan = () => {
    if (isRunning) return;
    setIsRunning(true);
    setIsErrorState(false);
    setIsCompiled(false);
  };

  const applyQuickFix = () => {
    setBlocks(prev =>
      prev.map(b => {
        if (b.status === 'failed') {
          return {
            ...b,
            value: 20,
            actualType: 'int',
            status: 'pending',
            progress: 45 // Back up slightly for the scanner to re-verify
          };
        }
        return b;
      })
    );
    setIsErrorState(false);
    setIsRunning(true);
  };

  const resetSorter = () => {
    setIsRunning(false);
    setIsErrorState(false);
    setIsCompiled(false);
    setHoveredError(false);
    setBlocks([
      // Spaced out on the pipeline: progress 0, 18, 36
      {
        id: '1',
        name: 'score',
        value: 42,
        actualType: 'int',
        expectedType: 'int',
        progress: 0,
        status: 'pending'
      },
      {
        id: '2',
        name: 'active',
        value: 'true',
        actualType: 'boolean',
        expectedType: 'boolean',
        progress: 18,
        status: 'pending'
      },
      {
        id: '3',
        name: 'limit',
        value: '"twenty"',
        actualType: 'string', // type mismatch!
        expectedType: 'int',
        progress: 36,
        status: 'pending'
      }
    ]);
  };

  // Find the block currently at the scanner gate
  const blockAtGate = blocks.find(b => b.progress >= 45 && b.progress <= 55);

  return (
    <div 
      ref={containerRef}
      className="w-full bg-[#09090b] rounded-2xl border border-zinc-800/80 p-6 md:p-8 flex flex-col gap-6 md:gap-8 shadow-2xl relative overflow-hidden group"
    >
      {/* Background radial gradient */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_800px_at_100%_0%,rgba(34,211,238,0.03),transparent)] pointer-events-none" />

      {/* Header Info */}
      <div className="flex justify-between items-start gap-4">
        <div>
          <span className="text-[10px] font-bold tracking-widest text-cyan-400 uppercase bg-cyan-950/40 border border-cyan-800/50 px-2 py-0.5 rounded">
            Strict Typing
          </span>
          <h3 className="text-xl md:text-2xl font-bold text-white mt-2">Type-Gate Compiler Scanner</h3>
        </div>
        <div className="flex items-center gap-1.5 bg-zinc-900 border border-zinc-800 px-3 py-1.5 rounded-lg">
          <ShieldCheck className="w-4 h-4 text-cyan-400" />
          <span className="text-xs font-mono font-medium text-zinc-300">
            Scanner: <span className="text-cyan-400 font-semibold">Pre-execution Guard</span>
          </span>
        </div>
      </div>

      <div className="grid md:grid-cols-5 gap-6 items-stretch">
        {/* Left Side: Code View */}
        <div className="md:col-span-2 flex flex-col gap-4">
          <div className="bg-black border border-zinc-850 rounded-xl p-4 font-mono text-[11px] md:text-xs text-zinc-300 relative shadow-inner flex-grow flex flex-col justify-center">
            <div className="text-zinc-650 select-none pb-2 border-b border-zinc-900 mb-2 font-semibold text-[10px] tracking-wider uppercase flex items-center gap-1.5">
              <FileCode className="w-3.5 h-3.5 text-cyan-500" />
              COMPILE CONTRACT (main.nox)
            </div>
            
            <div className="leading-relaxed flex flex-col gap-1">
              <div>main() {'{'}</div>
              <div className="pl-4">int score = <span className="text-yellow-400">42</span>;</div>
              <div className="pl-4">boolean active = <span className="text-cyan-400">true</span>;</div>
              <div className="pl-4 relative inline-block">
                <span>int limit = </span>
                {!isCompiled && !blocks.find(b => b.id === '3' && b.actualType === 'int') ? (
                  <span 
                    className="relative cursor-help font-bold text-red-400 underline decoration-wavy decoration-red-500 underline-offset-4"
                    onMouseEnter={() => setHoveredError(true)}
                    onMouseLeave={() => setHoveredError(false)}
                  >
                    "twenty"
                  </span>
                ) : (
                  <span className="text-yellow-400 font-bold">20</span>
                )}
                <span>;</span>
                
                {/* Hover Compiler Diagnostic popover */}
                {(hoveredError || isErrorState) && !isCompiled && !blocks.find(b => b.id === '3' && b.actualType === 'int') && (
                  <div className="absolute left-0 top-6 bg-red-950/95 border border-red-800 text-red-300 p-3 rounded-lg w-[240px] z-20 shadow-2xl font-sans text-xs leading-normal animate-[scaleUp_0.15s_ease-out]">
                    <div className="font-bold font-mono text-red-400 flex items-center gap-1.5 mb-1 text-[11px]">
                      <AlertCircle className="w-3.5 h-3.5" />
                      Nox CompileError
                    </div>
                    Variable type mismatch: variable 'limit' is declared as 'int' but initializer has type 'string'.
                  </div>
                )}
              </div>
              <div>{'}'}</div>
            </div>
          </div>

          {/* Controls */}
          <div className="flex gap-2">
            {isErrorState ? (
              <button
                onClick={applyQuickFix}
                className="flex-grow py-3 px-4 rounded-xl bg-cyan-500 text-black font-extrabold text-sm hover:bg-cyan-400 transition-colors flex items-center justify-center gap-2 shadow-[0_0_20px_rgba(6,182,212,0.3)] cursor-pointer animate-[pulse_2s_infinite]"
              >
                <Sparkles className="w-4 h-4" />
                Apply Quick Fix (Type Int)
              </button>
            ) : (
              <button
                onClick={startScan}
                disabled={isRunning || isCompiled}
                className="flex-grow py-3 px-4 rounded-xl bg-white text-black font-bold text-sm hover:bg-zinc-200 transition-colors flex items-center justify-center gap-2 shadow-lg disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
              >
                <Play className="w-4 h-4 fill-current" />
                {isRunning ? 'Analyzing AST...' : isCompiled ? 'Compiled' : 'Scan & Compile'}
              </button>
            )}

            <button
              onClick={resetSorter}
              className="p-3 rounded-xl bg-zinc-900 text-zinc-400 border border-zinc-800 hover:bg-zinc-800 hover:text-white transition-colors cursor-pointer"
              title="Reset"
            >
              <RotateCcw className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Right Side: Visual Single-Lane Pipeline */}
        <div className="md:col-span-3 flex flex-col justify-between bg-zinc-950/40 border border-zinc-850 p-6 rounded-xl relative min-h-[260px] overflow-hidden">
          
          {/* Top Panel: Scanner Display */}
          <div className="flex justify-between items-center bg-black/60 border border-zinc-800/80 px-4 py-2.5 rounded-lg z-10">
            <span className="text-[10px] font-mono text-zinc-550 uppercase tracking-widest">Compiler Gate Status</span>
            <div className="flex items-center gap-2 font-mono text-xs">
              <span className="text-zinc-550">Contract:</span>
              <span className={`font-bold transition-all ${
                blockAtGate 
                  ? 'text-cyan-400' 
                  : 'text-zinc-500'
              }`}>
                {blockAtGate ? `Expected ${blockAtGate.expectedType.toUpperCase()}` : 'WAITING...'}
              </span>
            </div>
          </div>

          {/* Conveyor Belt Space */}
          <div className="relative h-28 w-full flex items-center my-4">
            
            {/* The Conveyor Rail line */}
            <div className="absolute left-0 right-0 h-1 bg-zinc-900 border-b border-zinc-800/80 top-[50%] -translate-y-[50%] pointer-events-none" />

            {/* Sandbox VM Entry Portal (Right 15%) */}
            <div className="absolute right-0 top-[50%] -translate-y-[50%] w-14 h-14 rounded-full border border-cyan-800/40 bg-black flex flex-col items-center justify-center shadow-[inset_0_0_12px_rgba(6,182,212,0.2)] z-10">
              <span className="text-[8px] font-bold font-mono tracking-widest text-cyan-500 uppercase">VM</span>
              <span className="text-[8px] font-mono text-zinc-650 uppercase font-semibold">SAFE</span>
            </div>

            {/* Central Scanner Gate (Middle 50%) */}
            <div className="absolute left-[50%] -translate-x-[50%] top-[50%] -translate-y-[50%] z-20 flex flex-col items-center">
              
              {/* Dynamic Gate Display Frame */}
              <div className={`w-14 h-20 rounded-xl border flex flex-col items-center justify-center transition-all duration-300 bg-[#060608] ${
                isErrorState
                  ? 'border-red-500 shadow-[0_0_18px_rgba(239,68,68,0.3)]'
                  : isCompiled
                    ? 'border-emerald-500 shadow-[0_0_15px_rgba(16,185,129,0.25)]'
                    : blockAtGate?.status === 'passed'
                      ? 'border-emerald-500 shadow-[0_0_15px_rgba(16,185,129,0.25)]'
                      : 'border-zinc-800'
              }`}>
                <div className="text-[7px] font-bold font-mono tracking-widest text-zinc-550 uppercase mb-2">
                  SCANNER
                </div>
                
                {isErrorState ? (
                  <div className="flex flex-col items-center justify-center w-8 h-8 rounded border border-red-500/50 bg-red-950/20 text-red-500 font-mono font-black text-xs animate-pulse">
                    FAIL
                  </div>
                ) : blockAtGate ? (
                  <div className="flex flex-col items-center justify-center w-8 h-8 rounded border border-cyan-500/30 bg-cyan-950/10 text-cyan-400 font-mono font-bold text-[9px] animate-[pulse_1s_infinite]">
                    SCAN
                  </div>
                ) : (
                  <ShieldCheck className="w-5 h-5 text-zinc-800" />
                )}
              </div>

              {/* Scanning Laser Beam */}
              <div 
                className={`absolute top-[-20px] bottom-[-20px] w-0.5 pointer-events-none transition-all duration-300 ${
                  isErrorState 
                    ? 'bg-red-500 shadow-[0_0_10px_rgba(239,68,68,1),_0_0_20px_rgba(239,68,68,0.5)]' 
                    : isRunning 
                      ? 'bg-cyan-400 shadow-[0_0_10px_rgba(6,182,212,1),_0_0_20px_rgba(6,182,212,0.5)] animate-[pulse_1s_infinite]' 
                      : 'bg-zinc-800 opacity-20'
                }`}
              />
            </div>

            {/* Sequential Token Boxes traveling down the conveyor belt */}
            {blocks.map(block => {
              const isBlockFailed = block.status === 'failed';
              const isBlockPassed = block.status === 'passed';
              
              return (
                <div
                  key={block.id}
                  className={`absolute flex flex-col items-center justify-between border bg-black/95 rounded-lg px-2.5 py-1.5 shadow-lg transition-all w-24 h-14 z-15 ${
                    isBlockFailed
                      ? 'border-red-500 shadow-[0_0_15px_rgba(239,68,68,0.4)] animate-pulse'
                      : isBlockPassed
                        ? 'border-emerald-500/80 shadow-[0_0_8px_rgba(16,185,129,0.2)]'
                        : 'border-zinc-800 hover:border-zinc-700'
                  }`}
                  style={{
                    left: `calc(${block.progress}% - 48px)`,
                    top: '50%',
                    transform: 'translateY(-50%)',
                    opacity: block.progress >= 95 ? 0 : 1,
                  }}
                >
                  {/* Top metadata row: Name + Type tag */}
                  <div className="flex justify-between items-center w-full">
                    <span className="text-[8px] font-mono font-bold text-zinc-400">{block.name}</span>
                    <span className={`text-[7px] font-mono uppercase px-1 rounded-sm border font-extrabold ${
                      isBlockFailed
                        ? 'bg-red-950/40 border-red-800/80 text-red-400'
                        : isBlockPassed
                          ? 'bg-emerald-950/40 border-emerald-800/80 text-emerald-400'
                          : 'bg-cyan-950/20 border-cyan-900/60 text-cyan-400'
                    }`}>
                      {block.actualType}
                    </span>
                  </div>
                  
                  {/* Center Value display */}
                  <div className={`text-[10px] font-mono font-bold tracking-tight truncate max-w-full ${
                    isBlockFailed
                      ? 'text-red-400'
                      : 'text-white'
                  }`}>
                    {block.value}
                  </div>

                  {/* Bottom boundary line indicator */}
                  <div className={`w-full h-0.5 rounded-full ${
                    isBlockFailed
                      ? 'bg-red-500'
                      : isBlockPassed
                        ? 'bg-emerald-500'
                        : 'bg-zinc-800'
                  }`} />
                </div>
              );
            })}
          </div>

          {/* Bottom Panel: Track guide */}
          <div className="flex justify-between items-center text-[10px] font-mono text-zinc-650 bg-black/20 border border-zinc-900 px-3 py-1 rounded">
            <span>Conveyor Rail 0</span>
            <span>Pre-Verification Buffer</span>
          </div>
        </div>
      </div>

      {/* Diagnostics Console Panel */}
      <div className="bg-[#020202] rounded-xl border border-zinc-850 p-4 font-mono text-[11px] md:text-xs leading-relaxed text-zinc-400 overflow-x-auto min-h-[110px] flex flex-col justify-center">
        {!isRunning && !isErrorState && !isCompiled ? (
          <div className="text-zinc-500 flex items-center gap-1.5">
            <span className="text-cyan-600 font-bold">compiler:</span> Ready. Click Scan & Compile to analyze typing contracts.
          </div>
        ) : isRunning ? (
          <div className="text-zinc-300 animate-pulse flex flex-col gap-0.5">
            <div><span className="text-cyan-600 font-bold">compiler:</span> Parsing token stream AST...</div>
            <div><span className="text-cyan-600 font-bold">compiler:</span> Performing type synthesis and contract verification...</div>
          </div>
        ) : isErrorState ? (
          <div className="text-red-400 flex flex-col gap-0.5">
            <div className="font-bold flex items-center gap-1.5 text-red-500">
              <AlertCircle className="w-3.5 h-3.5 shrink-0" />
              error: Variable type mismatch: variable 'limit' is declared as 'int' but initializer has type 'string'
            </div>
            <div className="text-zinc-400 pl-5">
              &nbsp;&nbsp;--&gt; main.nox:4:17
              <br />
              &nbsp;&nbsp;&nbsp;|&nbsp;
              <br />
              4 |&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;int limit = "twenty";
              <br />
              &nbsp;&nbsp;&nbsp;|&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;^^^^^^^^
              <br />
              &nbsp;&nbsp;&nbsp;=&nbsp;help: Use '.toInt(defaultValue)' to parse the string as an integer
            </div>
            <div className="text-cyan-400 font-semibold pl-5 mt-1.5 flex items-center gap-1.5 animate-pulse">
              <Check className="w-3.5 h-3.5 text-cyan-400 shrink-0" />
              Pre-execution guarantee: Runaway code blocked before VM initialization.
            </div>
          </div>
        ) : (
          <div className="text-cyan-400 flex flex-col gap-0.5">
            <div className="font-bold flex items-center gap-1.5 text-emerald-400">
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
              [COMPILED] Contracts Fully Verified
            </div>
            <div className="text-zinc-400 pl-5">
              All variable shapes successfully passed compilation gates.
              <br />
              Generated register-allocated instructions. Sandbox safe to execute.
            </div>
          </div>
        )}
      </div>

      <style>{`
        @keyframes scaleUp {
          from { transform: scale(0.9); opacity: 0; }
          to { transform: scale(1); opacity: 1; }
        }
      `}</style>
    </div>
  );
}
