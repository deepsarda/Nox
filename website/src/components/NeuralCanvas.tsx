import React, { useEffect, useRef } from 'react';

const easeOpacity = (x: number): number => {
  return x * x * x;
};

export default function NeuralCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let particles: any[] = [];
    let animationFrameId: number;
    let w = 0;
    let h = 0;
    
    // Track mouse position globally
    const mouse = { x: -1000, y: -1000, active: false };

    const handleMouseMove = (e: MouseEvent) => {
      mouse.x = e.clientX;
      mouse.y = e.clientY;
      mouse.active = true;
    };
    
    const handleMouseLeave = () => {
      mouse.active = false;
    };

    const initParticles = () => {
      particles = [];
      const particleCount = Math.min(Math.floor((w * h) / 10000), 200); // Scale count by screen size

      for (let i = 0; i < particleCount; i++) {
        // Assign layers: 0 (background/slow), 1 (midground/medium), 2 (foreground/fast)
        const layer = Math.random() > 0.8 ? 2 : Math.random() > 0.4 ? 1 : 0;
        
        const speedMult = layer === 2 ? 0.8 : layer === 1 ? 0.4 : 0.15;
        const radius = layer === 2 ? 2.5 : layer === 1 ? 1.5 : 0.8;
        
        particles.push({
          x: Math.random() * w,
          y: Math.random() * h,
          vx: (Math.random() - 0.5) * speedMult,
          vy: (Math.random() - 0.5) * speedMult,
          radius: radius,
          layer: layer,
          baseColor: layer === 2 ? 'rgba(34, 211, 238, 0.8)' : layer === 1 ? 'rgba(6, 182, 212, 0.4)' : 'rgba(8, 145, 178, 0.2)'
        });
      }
    };

    const resize = () => {
      w = window.innerWidth;
      h = window.innerHeight;
      canvas.width = w;
      canvas.height = h;
      initParticles();
    };

    window.addEventListener('resize', resize);
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseleave', handleMouseLeave);
    resize(); // Initial setup

    const maxDistance = 600;
    const mouseRepelRadius = 600;

    const draw = () => {
      // Clear with pure black for maximum contrast
      ctx.clearRect(0, 0, w, h);

      // Update positions & Mouse Interaction
      particles.forEach(p => {
        // Move
        p.x += p.vx;
        p.y += p.vy;

        // Mouse repulsion physics
        if (mouse.active) {
          const dx = p.x - mouse.x;
          const dy = p.y - mouse.y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          
          if (dist < mouseRepelRadius) {
            const force = (mouseRepelRadius - dist) / mouseRepelRadius;
            // Push particles away. Foreground (layer 2) reacts more violently than background (layer 0)
            const pushFactor = (p.layer + 1) * 0.5;
            p.x += (dx / dist) * force * pushFactor;
            p.y += (dy / dist) * force * pushFactor;
          }
        }

        // Screen wrapping (No spawning phase)
        if (p.x < -10) p.x = w + 10;
        if (p.x > w + 10) p.x = -10;
        if (p.y < -10) p.y = h + 10;
        if (p.y > h + 10) p.y = -10;
      });

      // Draw connections
      // We only connect particles in the SAME layer to enforce the 3D parallax depth effect
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const p1 = particles[i];
          const p2 = particles[j];

          if (p1.layer !== p2.layer) continue;

          const dx = p1.x - p2.x;
          const dy = p1.y - p2.y;
          const distSq = dx * dx + dy * dy;

          if (distSq < maxDistance * maxDistance) {
            const dist = Math.sqrt(distSq);
            
            // Linear distance normalized 0 to 1 (1 = touching, 0 = maxDistance apart)
            const normalizedDist = 1 - (dist / maxDistance);
            
            // Evolve: Use a non-linear curve for natural fade-in
            const opacity = easeOpacity(normalizedDist);
            
            // Base alpha scaling by layer
            const maxAlpha = p1.layer === 2 ? 0.5 : p1.layer === 1 ? 0.2 : 0.05;
            
            ctx.beginPath();
            ctx.moveTo(p1.x, p1.y);
            ctx.lineTo(p2.x, p2.y);
            ctx.strokeStyle = `rgba(6, 182, 212, ${opacity * maxAlpha})`;
            ctx.lineWidth = p1.layer === 2 ? 1.5 : p1.layer === 1 ? 1.0 : 0.5;
            ctx.stroke();
          }
        }
      }

      // Draw nodes
      particles.forEach(p => {
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
        ctx.fillStyle = p.baseColor;
        ctx.fill();
      });

      animationFrameId = requestAnimationFrame(draw);
    };

    draw();

    return () => {
      window.removeEventListener('resize', resize);
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseleave', handleMouseLeave);
      cancelAnimationFrame(animationFrameId);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="absolute inset-0 z-0 pointer-events-auto"
      style={{ display: 'block', width: '100vw', height: '100vh', opacity: 0.8 }}
    />
  );
}