import React, { useState, useEffect, useRef } from 'react';
import { Shield, Play, RotateCcw, AlertTriangle, CheckCircle, Sliders } from 'lucide-react';

interface Particle {
  id: number;
  x: number;
  y: number;
  vx: number;
  vy: number;
  color: string;
  size: number;
}

export default function ResourceGuardSimulator() {
  const [scriptType, setScriptType] = useState<'safe' | 'runaway'>('runaway');
  const [maxInstructions, setMaxInstructions] = useState<number>(400000);
  const [instructionCount, setInstructionCount] = useState<number>(0);
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [isViolated, setIsViolated] = useState<boolean>(false);
  const [isSuccess, setIsSuccess] = useState<boolean>(false);
  const [particles, setParticles] = useState<Particle[]>([]);
  const containerRef = useRef<HTMLDivElement>(null);
  const hasAutoRunRef = useRef<boolean>(false);
  
  // Animation loops
  useEffect(() => {
    if (!isRunning) {
      // Idle float particles
      const idleInterval = setInterval(() => {
        setParticles(prev => 
          prev.map(p => {
            let vx = p.vx + (Math.random() - 0.5) * 0.1;
            let vy = p.vy + (Math.random() - 0.5) * 0.1;
            // Dampen speed
            vx *= 0.95;
            vy *= 0.95;
            
            // Constrain inside circular sandbox (radius ~70px from center 100, 100)
            const dx = (p.x + vx) - 100;
            const dy = (p.y + vy) - 100;
            const dist = Math.sqrt(dx * dx + dy * dy);
            
            if (dist > 65) {
              // Bounce back
              return {
                ...p,
                vx: -vx * 0.8,
                vy: -vy * 0.8,
                x: 100 + (dx / dist) * 64,
                y: 100 + (dy / dist) * 64
              };
            }
            
            return { ...p, x: p.x + vx, y: p.y + vy, vx, vy };
          })
        );
      }, 30);
      return () => clearInterval(idleInterval);
    }

    // Active running logic
    let currentCount = 0;
    const targetCount = scriptType === 'safe' ? 145000 : maxInstructions;
    const startTime = Date.now();
    const duration = scriptType === 'safe' ? 2000 : 3500; // time in ms
    
    // Initialize active particles
    const initParticles: Particle[] = [];
    const count = scriptType === 'safe' ? 12 : 25;
    for (let i = 0; i < count; i++) {
      const angle = Math.random() * Math.PI * 2;
      const r = Math.random() * 40;
      initParticles.push({
        id: i,
        x: 100 + Math.cos(angle) * r,
        y: 100 + Math.sin(angle) * r,
        vx: (Math.random() - 0.5) * 2,
        vy: (Math.random() - 0.5) * 2,
        color: scriptType === 'safe' ? 'rgba(34, 211, 238, 0.8)' : 'rgba(239, 68, 68, 0.8)',
        size: Math.random() * 3 + 2
      });
    }
    setParticles(initParticles);

    const runInterval = setInterval(() => {
      const elapsed = Date.now() - startTime;
      const progress = Math.min(elapsed / duration, 1);
      
      // Animate instruction count (non-linear for runaway to simulate exponential loop)
      let countProgress = progress;
      if (scriptType === 'runaway') {
        // Exponential feel
        countProgress = Math.pow(progress, 3);
      }
      
      currentCount = Math.floor(countProgress * targetCount);
      setInstructionCount(currentCount);

      // Update particle physics
      setParticles(prev => {
        // Runaway script causes particles to multiply and speed up
        let updated = prev.map(p => {
          let speedFactor = scriptType === 'runaway' ? 1 + progress * 4 : 1.2;
          let vx = p.vx + (Math.random() - 0.5) * 0.5 * speedFactor;
          let vy = p.vy + (Math.random() - 0.5) * 0.5 * speedFactor;
          
          // Speed clamp
          const speed = Math.sqrt(vx * vx + vy * vy);
          const maxSpeed = 4 * speedFactor;
          if (speed > maxSpeed) {
            vx = (vx / speed) * maxSpeed;
            vy = (vy / speed) * maxSpeed;
          }

          let x = p.x + vx;
          let y = p.y + vy;
          
          // Circle boundary bounce
          const dx = x - 100;
          const dy = y - 100;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist > 68) {
            return {
              ...p,
              vx: -vx * 0.9,
              vy: -vy * 0.9,
              x: 100 + (dx / dist) * 67,
              y: 100 + (dy / dist) * 67
            };
          }
          return { ...p, x, y, vx, vy };
        });

        // Spawn new particles for runaway loop
        if (scriptType === 'runaway' && Math.random() < 0.25 && updated.length < 75) {
          const angle = Math.random() * Math.PI * 2;
          updated.push({
            id: Date.now() + Math.random(),
            x: 100,
            y: 100,
            vx: Math.cos(angle) * 2,
            vy: Math.sin(angle) * 2,
            color: 'rgba(239, 68, 68, 0.8)',
            size: Math.random() * 3 + 2
          });
        }

        return updated;
      });

      if (progress >= 1) {
        clearInterval(runInterval);
        setIsRunning(false);
        if (scriptType === 'runaway') {
          setIsViolated(true);
          // Explode/vaporize particles visually
          setParticles([]);
        } else {
          setIsSuccess(true);
        }
      }
    }, 30);

    return () => clearInterval(runInterval);
  }, [isRunning, scriptType, maxInstructions]);

  // Initial particles on mount
  useEffect(() => {
    const initial: Particle[] = [];
    for (let i = 0; i < 8; i++) {
      const angle = Math.random() * Math.PI * 2;
      const r = Math.random() * 50;
      initial.push({
        id: i,
        x: 100 + Math.cos(angle) * r,
        y: 100 + Math.sin(angle) * r,
        vx: (Math.random() - 0.5) * 0.4,
        vy: (Math.random() - 0.5) * 0.4,
        color: 'rgba(161, 161, 170, 0.5)',
        size: Math.random() * 2 + 1.5
      });
    }
    setParticles(initial);
  }, []);

  // Intersection Observer for scroll-to-trigger auto-run (runs once)
  useEffect(() => {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !hasAutoRunRef.current && !isRunning) {
          hasAutoRunRef.current = true;
          triggerSimulation();
        }
      },
      { threshold: 0.3 }
    );
    if (containerRef.current) {
      observer.observe(containerRef.current);
    }
    return () => observer.disconnect();
  }, [containerRef, isRunning]);

  const triggerSimulation = () => {
    if (isRunning) return;
    setIsViolated(false);
    setIsSuccess(false);
    setInstructionCount(0);
    setIsRunning(true);
  };

  const resetSimulation = () => {
    setIsRunning(false);
    setIsViolated(false);
    setIsSuccess(false);
    setInstructionCount(0);
    // Reset to initial passive particles
    const initial: Particle[] = [];
    for (let i = 0; i < 8; i++) {
      const angle = Math.random() * Math.PI * 2;
      const r = Math.random() * 50;
      initial.push({
        id: i,
        x: 100 + Math.cos(angle) * r,
        y: 100 + Math.sin(angle) * r,
        vx: (Math.random() - 0.5) * 0.4,
        vy: (Math.random() - 0.5) * 0.4,
        color: 'rgba(161, 161, 170, 0.5)',
        size: Math.random() * 2 + 1.5
      });
    }
    setParticles(initial);
  };

  return (
    <div 
      ref={containerRef}
      className="w-full bg-[#09090b] rounded-2xl border border-zinc-800/80 p-6 md:p-8 flex flex-col gap-6 md:gap-8 shadow-2xl relative overflow-hidden group"
    >
      {/* Background radial gradient */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_800px_at_100%_200px,rgba(6,182,212,0.03),transparent)] pointer-events-none" />

      {/* Header Info */}
      <div className="flex justify-between items-start gap-4">
        <div>
          <span className="text-[10px] font-bold tracking-widest text-cyan-400 uppercase bg-cyan-950/40 border border-cyan-800/50 px-2 py-0.5 rounded">
            Resource Guards
          </span>
          <h3 className="text-xl md:text-2xl font-bold text-white mt-2">VM Containment Shield</h3>
        </div>
        <div className="flex items-center gap-2 bg-zinc-900 border border-zinc-800 px-3 py-1.5 rounded-lg">
          <Shield className={`w-4 h-4 ${isViolated ? 'text-red-500 animate-pulse' : isSuccess ? 'text-cyan-400' : 'text-zinc-500'}`} />
          <span className="text-xs font-mono font-medium text-zinc-300">
            Host: <span className={isViolated ? 'text-red-400 font-semibold' : 'text-cyan-400 font-semibold'}>{isViolated ? 'PROTECTED' : 'SECURE'}</span>
          </span>
        </div>
      </div>

      <div className="grid md:grid-cols-5 gap-6 items-center">
        {/* Left Side: Settings & Controls */}
        <div className="md:col-span-2 flex flex-col gap-5">
          <div className="bg-black/40 border border-zinc-800/60 rounded-xl p-4 flex flex-col gap-4">
            <h4 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider flex items-center gap-1.5">
              <Sliders className="w-3.5 h-3.5 text-cyan-500" />
              Configure VM Guard
            </h4>
            
            {/* Limit Selector */}
            <div>
              <div className="flex justify-between text-xs font-mono text-zinc-400 mb-1.5">
                <span>Instruction Limit</span>
                <span className="text-cyan-400 font-medium">{maxInstructions.toLocaleString()} inst.</span>
              </div>
              <input 
                type="range" 
                min="100000" 
                max="1000000" 
                step="50000"
                value={maxInstructions} 
                disabled={isRunning}
                onChange={(e) => setMaxInstructions(parseInt(e.target.value))}
                className="w-full accent-cyan-500 h-1 bg-zinc-800 rounded-lg cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              />
            </div>

            {/* Script Type selector */}
            <div className="flex gap-2">
              <button
                onClick={() => setScriptType('runaway')}
                disabled={isRunning}
                className={`flex-1 py-2 px-3 text-xs font-semibold rounded-lg border transition-all ${
                  scriptType === 'runaway' 
                    ? 'bg-red-950/20 text-red-400 border-red-900/60 shadow-[0_0_12px_rgba(239,68,68,0.15)]' 
                    : 'bg-zinc-900/50 text-zinc-400 border-zinc-800 hover:text-zinc-300'
                } disabled:opacity-50`}
              >
                Malicious Script
              </button>
              <button
                onClick={() => setScriptType('safe')}
                disabled={isRunning}
                className={`flex-1 py-2 px-3 text-xs font-semibold rounded-lg border transition-all ${
                  scriptType === 'safe' 
                    ? 'bg-cyan-950/20 text-cyan-400 border-cyan-900/60 shadow-[0_0_12px_rgba(6,182,212,0.15)]' 
                    : 'bg-zinc-900/50 text-zinc-400 border-zinc-800 hover:text-zinc-300'
                } disabled:opacity-50`}
              >
                Safe Script
              </button>
            </div>
          </div>

          <div className="flex gap-2">
            <button
              onClick={triggerSimulation}
              disabled={isRunning}
              className="flex-grow py-3 px-4 rounded-xl bg-white text-black font-bold text-sm hover:bg-zinc-200 transition-colors flex items-center justify-center gap-2 shadow-lg disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
            >
              <Play className="w-4 h-4 fill-current" />
              {isRunning ? 'Executing...' : 'Run Simulation'}
            </button>
            
            <button
              onClick={resetSimulation}
              className="p-3 rounded-xl bg-zinc-900 text-zinc-400 border border-zinc-800 hover:bg-zinc-800 hover:text-white transition-colors cursor-pointer"
              title="Reset"
            >
              <RotateCcw className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* Right Side: The Sandbox Visualization */}
        <div className="md:col-span-3 flex flex-col items-center justify-center py-4 bg-zinc-950/40 rounded-xl border border-zinc-850 p-4 relative min-h-[260px]">
          {/* Dynamic instruction counter */}
          <div className="absolute top-4 left-4 font-mono text-[11px] text-zinc-500 z-10 flex flex-col">
            <span>VM Instruction Counter:</span>
            <span className={`text-base font-semibold ${isViolated ? 'text-red-400' : 'text-cyan-400'}`}>
              {instructionCount.toLocaleString()}
            </span>
          </div>

          {/* Host Health Indicator */}
          <div className="absolute top-4 right-4 text-[10px] font-mono text-zinc-500 z-10 text-right flex flex-col">
            <span>Host CPU Core 0:</span>
            <span className="text-emerald-400 font-semibold">0.0% (Fully Responsive)</span>
          </div>

          {/* Sandbox Physics Display */}
          <div className="relative w-[200px] h-[200px]">
            {/* Dotted Outer Ring - Host Boundaries */}
            <div className="absolute inset-0 rounded-full border border-dashed border-zinc-800/80 animate-[spin_100s_linear_infinite]" />
            
            {/* Inner VM Container - Solid with dynamic glow based on state */}
            <div className={`absolute inset-6 rounded-full border transition-all duration-300 flex items-center justify-center ${
              isViolated 
                ? 'border-red-500 bg-red-950/10 shadow-[0_0_30px_rgba(239,68,68,0.4)]' 
                : isRunning
                  ? scriptType === 'runaway'
                    ? 'border-orange-500 bg-orange-950/5 shadow-[0_0_20px_rgba(249,115,22,0.2)]'
                    : 'border-cyan-500 bg-cyan-950/5 shadow-[0_0_20px_rgba(6,182,212,0.2)]'
                  : 'border-zinc-800 bg-black'
            }`}>
              {/* Central Text Message */}
              <div className="text-center px-4 z-10 pointer-events-none select-none">
                {!isRunning && !isViolated && !isSuccess && (
                  <span className="text-[10px] font-bold tracking-wider text-zinc-600 uppercase">VM IDLE</span>
                )}
                {isRunning && (
                  <span className={`text-[10px] font-bold tracking-widest uppercase animate-pulse ${
                    scriptType === 'runaway' ? 'text-red-400' : 'text-cyan-400'
                  }`}>
                    VM EXECUTING
                  </span>
                )}
                {isViolated && (
                  <div className="flex flex-col items-center gap-1 animate-[scaleUp_0.2s_ease-out]">
                    <AlertTriangle className="w-5 h-5 text-red-500" />
                    <span className="text-[10px] font-black tracking-widest text-red-500 uppercase">HALTED</span>
                  </div>
                )}
                {isSuccess && (
                  <div className="flex flex-col items-center gap-1 animate-[scaleUp_0.2s_ease-out]">
                    <CheckCircle className="w-5 h-5 text-cyan-400" />
                    <span className="text-[10px] font-black tracking-widest text-cyan-400 uppercase">SUCCESS</span>
                  </div>
                )}
              </div>

              {/* Secure containment shield overlay when violated */}
              {isViolated && (
                <div className="absolute inset-0 rounded-full border-4 border-red-500/80 animate-ping opacity-20" />
              )}
            </div>

            {/* Custom SVG canvas for drawing floating nodes inside circular VM space */}
            <div className="absolute inset-0 pointer-events-none">
              <svg width="200" height="200" className="w-full h-full">
                {/* Draw particles */}
                {particles.map(p => (
                  <circle
                    key={p.id}
                    cx={p.x}
                    cy={p.y}
                    r={p.size}
                    fill={p.color}
                    className="transition-all duration-75"
                    style={{
                      filter: 'drop-shadow(0 0 2px currentColor)'
                    }}
                  />
                ))}

                {/* Connecting lines inside sandbox for sci-fi cluster effect */}
                {isRunning && particles.length > 1 && (
                  particles.slice(0, 8).map((p1, idx) => {
                    const p2 = particles[(idx + 1) % particles.length];
                    // Only connect if distance is small
                    const dx = p1.x - p2.x;
                    const dy = p1.y - p2.y;
                    const dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 45) {
                      return (
                        <line
                          key={`l-${idx}`}
                          x1={p1.x}
                          y1={p1.y}
                          x2={p2.x}
                          y2={p2.y}
                          stroke={scriptType === 'runaway' ? 'rgba(239, 68, 68, 0.2)' : 'rgba(6, 182, 212, 0.2)'}
                          strokeWidth="0.8"
                        />
                      );
                    }
                    return null;
                  })
                )}
              </svg>
            </div>
          </div>
        </div>
      </div>

      {/* Terminal log response block */}
      <div className="bg-[#020202] rounded-xl border border-zinc-850 p-4 font-mono text-[11px] md:text-xs leading-relaxed text-zinc-400 overflow-x-auto min-h-[90px] flex flex-col justify-center">
        {(!isRunning && !isViolated && !isSuccess) && (
          <div className="text-zinc-500 flex items-center gap-1.5">
            <span className="text-cyan-600 font-bold">nox-vm:</span> Ready. Choose a script configuration and click Run Simulation.
          </div>
        )}
        {isRunning && (
          <div className="text-zinc-300 flex flex-col gap-0.5 animate-pulse">
            <div><span className="text-cyan-600 font-bold">nox-vm:</span> Compiling script to NSL Bytecode...</div>
            <div><span className="text-cyan-600 font-bold">nox-vm:</span> Initializing Sandbox VM (Limit: {maxInstructions.toLocaleString()} instructions).</div>
            <div><span className="text-cyan-600 font-bold">nox-vm:</span> Booting sandbox (1.2µs startup)...</div>
          </div>
        )}
        {isViolated && (
          <div className="text-red-400 flex flex-col gap-0.5">
            <div className="font-bold flex items-center gap-1.5 text-red-500">
              <AlertTriangle className="w-3.5 h-3.5" />
              [CRITICAL] VM Exception: QuotaExceededError
            </div>
            <div className="text-zinc-400 pl-5">
              Execution limit exceeded: {maxInstructions.toLocaleString()} instructions.
              <br />
              at sandbox.main (runaway_script.nox:3)
              <br />
              at Nox.VM.Executor.execute(instructionCount={instructionCount.toLocaleString()}, maxLimit={maxInstructions.toLocaleString()})
            </div>
            <div className="text-emerald-400 font-bold pl-5 mt-1 flex items-center gap-1.5">
              <Shield className="w-3.5 h-3.5 text-emerald-400" />
              Host Protected: VM terminated in 0.00ms. Host CPU thread fully isolated.
            </div>
          </div>
        )}
        {isSuccess && (
          <div className="text-cyan-400 flex flex-col gap-0.5">
            <div className="font-bold flex items-center gap-1.5 text-emerald-400">
              <CheckCircle className="w-3.5 h-3.5" />
              [SUCCESS] Execution complete in 145,000 instructions
            </div>
            <div className="text-zinc-400 pl-5">
              VM exited normally with status code 0.
              <br />
              Total instructions executed: 145,000 / {maxInstructions.toLocaleString()} limit.
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
