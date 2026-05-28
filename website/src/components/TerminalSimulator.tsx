import React, { useState, useEffect } from 'react';

export default function TerminalSimulator() {
    const [lines, setLines] = useState<{text: string, type: 'cmd'|'info'|'success'|'error'|'prompt'}[]>([]);
    
    useEffect(() => {
        const sequence = [
            { text: "$ nox run agent.nox", type: "cmd" as const },
            { text: "[info] Compiling NSL bytecode...", type: "info" as const },
            { text: "[info] Sandboxed VM initialized. Inst limits: 500k.", type: "info" as const },
            { text: "[prompt] Script is requesting access to network: 'api.internal'. Allow? (y/N)", type: "prompt" as const },
            { text: "y", type: "cmd" as const },
            { text: "[info] Permission granted. Executing main()...", type: "info" as const },
            { text: "Analysis complete. Found 2419 entries.", type: "success" as const },
            { text: "[info] Process exited with code 0.", type: "info" as const }
        ];
        
        let i = 0;
        let interval: NodeJS.Timeout | null = null;
        
        setLines([]); // Reset on mount to fix StrictMode double-fire bugs
        
        // Start outputting after a delay, simulating the editor finishing
        const timeout = setTimeout(() => {
            interval = setInterval(() => {
                if (i < sequence.length) {
                    // Make the user prompt pause slightly longer before hitting 'y'
                    if (sequence[i].text === "y" && i > 0 && sequence[i-1].type === "prompt") {
                        clearInterval(interval!);
                        setTimeout(() => {
                            setLines(sequence.slice(0, i + 1));
                            i++;
                            interval = setInterval(() => {
                                if (i < sequence.length) {
                                    setLines(sequence.slice(0, i + 1));
                                    i++;
                                } else {
                                    if (interval) clearInterval(interval);
                                }
                            }, 600);
                        }, 1200);
                        return;
                    }
                    
                    setLines(sequence.slice(0, i + 1));
                    i++;
                } else {
                    if (interval) clearInterval(interval);
                }
            }, 600);
        }, 4000);
        
        return () => {
            clearTimeout(timeout);
            if (interval) clearInterval(interval);
        };
    }, []);

    const isComplete = lines.length === 8; // sequence.length

    return (
        <div className="w-full h-full min-h-[380px] bg-[#000000] rounded-xl border border-zinc-800 shadow-2xl flex flex-col overflow-hidden ring-1 ring-white/5">
            {/* Header */}
            <div className="h-10 border-b border-zinc-800 bg-[#09090b] flex items-center px-4 shrink-0 justify-between">
                <div className="flex gap-2">
                    <span className="text-xs text-zinc-500 font-mono">bash - 80x24</span>
                </div>
            </div>
            
            {/* Terminal Content */}
            <div className="p-4 overflow-auto flex-grow font-mono text-[13px] leading-relaxed transition-all duration-300">
                {lines.map((line, idx) => (
                    <div 
                        key={idx} 
                        className={`mb-1 animate-fade-in ${
                            line.type === 'cmd' ? 'text-zinc-300' : 
                            line.type === 'info' ? 'text-zinc-500' : 
                            line.type === 'prompt' ? 'text-yellow-400 font-medium' : 
                            line.type === 'success' ? 'text-cyan-400 font-medium' : 
                            'text-red-400'
                        }`}
                    >
                        {line.text}
                    </div>
                ))}
                {!isComplete && lines.length > 0 && (
                    <div className="mt-2 text-zinc-500">
                        <span className="animate-pulse">▊</span>
                    </div>
                )}
                {isComplete && (
                    <div className="mt-2 text-zinc-300">
                        $ <span className="animate-pulse text-zinc-500">▊</span>
                    </div>
                )}
            </div>
        </div>
    );
}
