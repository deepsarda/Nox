import React, { useState, useEffect } from 'react';

const codeLines = [
    { text: '@tool:name ', color: 'text-zinc-500' }, { text: '"log_analyzer"\n', color: 'text-emerald-400' },
    { text: '@tool:description ', color: 'text-zinc-500' }, { text: '"Analyzes server logs securely"\n\n', color: 'text-emerald-400' },
    { text: '// Deterministic execution with memory guards\n', color: 'text-zinc-600 italic' },
    { text: 'main', color: 'text-cyan-400' }, { text: '(', color: 'text-zinc-400' }, { text: 'string ', color: 'text-purple-400' }, { text: 'endpoint = ', color: 'text-zinc-400' }, { text: '"https://api.internal/logs"', color: 'text-emerald-400' }, { text: ') {\n', color: 'text-zinc-400' },
    { text: '    json ', color: 'text-purple-400' }, { text: 'response = ', color: 'text-zinc-400' }, { text: 'Http', color: 'text-orange-400' }, { text: '.getJson(', color: 'text-cyan-400' }, { text: 'endpoint', color: 'text-zinc-300' }, { text: ');\n\n', color: 'text-zinc-400' },
    { text: '    // Strict typing and safe casting\n', color: 'text-zinc-600 italic' },
    { text: '    int ', color: 'text-purple-400' }, { text: 'count = ', color: 'text-zinc-400' }, { text: 'response', color: 'text-zinc-300' }, { text: '.getInt(', color: 'text-cyan-400' }, { text: '"total"', color: 'text-emerald-400' }, { text: ', ', color: 'text-zinc-400' }, { text: '0', color: 'text-orange-400' }, { text: ');\n\n', color: 'text-zinc-400' },
    { text: '    return ', color: 'text-purple-400' }, { text: '`Analysis complete. Found ${', color: 'text-emerald-400' }, { text: 'count', color: 'text-zinc-300' }, { text: '} entries.`;\n', color: 'text-emerald-400' },
    { text: '}\n', color: 'text-zinc-400' }
];

export default function AnimatedEditor() {
    const [displayedLines, setDisplayedLines] = useState<{text: string, color: string}[]>([]);
    const [currentIndex, setCurrentIndex] = useState(0);
    const [isComplete, setIsComplete] = useState(false);

    useEffect(() => {
        if (currentIndex >= codeLines.length) {
            setIsComplete(true);
            return;
        }

        const currentToken = codeLines[currentIndex];
        
        // Random delay to simulate AI chunk generation or autocomplete
        const delay = Math.random() * 150 + 50;
        
        const timer = setTimeout(() => {
            setDisplayedLines(prev => [...prev, currentToken]);
            setCurrentIndex(i => i + 1);
        }, delay);

        return () => clearTimeout(timer);
    }, [currentIndex]);

    return (
        <div className="w-full h-full min-h-[380px] bg-[#09090b] rounded-xl border border-zinc-800 shadow-2xl flex flex-col overflow-hidden ring-1 ring-white/5">
            {/* Window Controls */}
            <div className="h-10 border-b border-zinc-800 bg-[#09090b] flex items-center px-4 shrink-0">
                <div className="flex gap-2">
                    <div className="w-3 h-3 rounded-full bg-zinc-700"></div>
                    <div className="w-3 h-3 rounded-full bg-zinc-700"></div>
                    <div className="w-3 h-3 rounded-full bg-zinc-700"></div>
                </div>
                <div className="mx-auto flex items-center gap-2 text-xs font-medium text-zinc-500 bg-zinc-900 px-3 py-1 rounded-md border border-zinc-800">
                    <svg className="w-3 h-3 text-cyan-500" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path></svg>
                    agent.nox
                </div>
            </div>
            
            {/* Editor Content */}
            <div className="p-5 overflow-auto flex-grow relative bg-[#09090b]">
                <div className="font-mono text-[13px] leading-relaxed whitespace-pre-wrap">
                    {displayedLines.map((line, idx) => (
                        <span key={idx} className={line.color}>{line.text}</span>
                    ))}
                    {!isComplete && (
                        <span className="inline-block w-2 h-4 bg-zinc-500 animate-pulse align-middle ml-0.5"></span>
                    )}
                </div>
            </div>
        </div>
    );
}
