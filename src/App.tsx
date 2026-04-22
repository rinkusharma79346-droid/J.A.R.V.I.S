/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect } from 'react';
import { motion } from 'motion/react';

export default function App() {
  const [time, setTime] = useState(0);
  const [step, setStep] = useState(14);
  const [isProcessing, setIsProcessing] = useState(false);

  useEffect(() => {
    const timeInterval = setInterval(() => {
      setTime((prev) => prev + 1);
    }, 1000);

    const actionInterval = setInterval(() => {
      setIsProcessing(true);
      setTimeout(() => {
        setIsProcessing(false);
        setStep((s) => (s < 50 ? s + 1 : 1));
      }, 1500);
    }, 5000);

    return () => {
      clearInterval(timeInterval);
      clearInterval(actionInterval);
    };
  }, []);

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <>
      <div className="mesh-bg"></div>
      <div className="relative min-h-screen w-full flex flex-col p-4 md:p-8">
        <motion.div 
          initial={{ y: -20, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          transition={{ duration: 0.6, ease: "easeOut" }}
          className="absolute top-0 left-0 w-full flex justify-center pt-4"
        >
          <div className="glass rounded-full px-6 py-2 flex items-center gap-6 border-b border-white/10 mt-2">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-cyan-400 animate-pulse" style={{ backgroundColor: '#00E5FF' }}></div>
              <span className="text-[10px] uppercase tracking-widest text-[#00E5FF] font-bold hidden sm:inline">JARVIS Active</span>
            </div>
            <div className="h-4 w-[1px] bg-white/20 hidden sm:block"></div>
            <div className="text-[10px] text-white/60 uppercase tracking-wider">Session Time: {formatTime(time)}</div>
            <button className="bg-red-500/80 text-[10px] font-bold text-white px-3 py-1 rounded-full hover:bg-red-600 uppercase tracking-tighter cursor-pointer">
              Kill Agent
            </button>
          </div>
        </motion.div>
        
        <motion.div 
          initial={{ scale: 0.95, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={{ duration: 0.7, delay: 0.2 }}
          className="flex flex-col items-center mt-20 mb-8 text-center shrink-0"
        >
          <h1 className="text-4xl md:text-5xl font-black text-white tracking-tighter cyan-glow mb-1">J.A.R.V.I.S.</h1>
          <p className="text-[#00E5FF]/80 text-[10px] md:text-xs tracking-[0.2em] md:tracking-[0.4em] uppercase font-medium">Autonomous Android Neural Engine</p>
        </motion.div>
        
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 flex-grow">
          <motion.div 
            initial={{ x: -30, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            transition={{ duration: 0.7, delay: 0.4 }}
            className="lg:col-span-8 flex flex-col gap-6"
          >
            <motion.div 
              className="glass rounded-3xl p-6 md:p-8 flex flex-col flex-grow"
              animate={{ 
                boxShadow: isProcessing ? '0 0 30px 0 rgba(0, 229, 255, 0.4)' : '0 8px 32px 0 rgba(0, 0, 0, 0.8)',
                borderColor: isProcessing ? 'rgba(0, 229, 255, 0.5)' : 'rgba(255, 255, 255, 0.1)'
              }}
              transition={{ duration: 0.8, ease: "easeInOut" }}
            >
              <div className="flex justify-between items-start mb-6 shrink-0">
                <div>
                  <h2 className="text-white text-lg font-semibold">Current Objective</h2>
                  <p className="text-white/40 text-sm">ReAct Loop Execution</p>
                </div>
                <div className="text-right">
                  <div className="text-2xl font-mono text-[#00E5FF] font-bold">
                    Step {step}<span className="text-white/20">/50</span>
                  </div>
                </div>
              </div>
              
              <div className="bg-black/40 rounded-2xl border border-white/5 p-4 md:p-6 flex-grow mb-6 overflow-y-auto">
                <div className="flex gap-4 items-start">
                  <div className="w-10 h-10 rounded-xl bg-white/5 border border-white/10 flex items-center justify-center shrink-0">
                    <svg className="w-5 h-5 text-[#00E5FF]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path>
                    </svg>
                  </div>
                  <div>
                    <div className="text-xs text-white/30 uppercase font-bold tracking-wider mb-1">Task Prompt</div>
                    <div className="text-white text-base md:text-lg leading-relaxed">
                      "Find the best-rated Italian restaurant in Manhattan with outdoor seating and save the address to my notes app."
                    </div>
                  </div>
                </div>
                <motion.div 
                  className="mt-6 md:mt-8 pt-6 md:pt-8 border-t border-white/5"
                  animate={{ opacity: isProcessing ? 0.3 : 1 }}
                  transition={{ duration: 0.4 }}
                >
                  <div className="flex items-center gap-3 mb-4 flex-wrap">
                    <div className="px-2 py-1 bg-[#00E5FF]/10 border border-[#00E5FF]/30 rounded text-[10px] text-[#00E5FF] font-bold uppercase">
                      Action: Tap
                    </div>
                    <span className="text-white/60 text-sm italic">Target: (x: 480, y: 1220)</span>
                  </div>
                  <p className="text-white/80 text-sm md:text-base leading-relaxed">
                    Reasoning: "The Yelp search results have loaded. I am tapping on 'Buvette Gastroteque' to verify the outdoor seating availability in the description section."
                  </p>
                </motion.div>
              </div>
              
              <div className="relative shrink-0">
                <input
                  type="text"
                  placeholder="Override next command..."
                  className="w-full bg-white/5 border border-white/10 rounded-2xl py-4 pl-4 pr-24 text-white placeholder:text-white/20 focus:outline-none focus:border-[#00E5FF]/50 transition-all font-sans text-sm md:text-base"
                />
                <button className="absolute right-2 top-2 bottom-2 bg-[#00E5FF] px-4 md:px-6 rounded-xl text-black font-bold text-xs md:text-sm uppercase tracking-wider hover:opacity-90 transition-opacity cursor-pointer">
                  Send
                </button>
              </div>
            </motion.div>
          </motion.div>
          
          <motion.div 
            initial={{ x: 30, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            transition={{ duration: 0.7, delay: 0.5 }}
            className="lg:col-span-4 flex flex-col gap-6 h-full"
          >
            <div className="glass rounded-3xl p-6 flex flex-col flex-1 min-h-[250px]">
              <h3 className="text-white/60 text-[11px] uppercase tracking-widest font-bold mb-4 shrink-0">System Status</h3>
              <div className="space-y-4 flex-grow">
                <div className="flex justify-between items-center">
                  <span className="text-white/40 text-sm">Accessibility</span>
                  <span className="text-emerald-400 text-sm font-bold">ACTIVE</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-white/40 text-sm">Overlay Draw</span>
                  <span className="text-emerald-400 text-sm font-bold">ACTIVE</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-white/40 text-sm">API Latency</span>
                  <span className="text-[#00E5FF] text-sm font-mono">142ms</span>
                </div>
                <div className="flex justify-between items-center">
                  <span className="text-white/40 text-sm">Context Window</span>
                  <span className="text-white/80 text-sm font-mono">4.2k / 1M</span>
                </div>
              </div>
              <div className="mt-4 pt-4 border-t border-white/10 shrink-0">
                <div className="text-[10px] text-white/30 mb-2 uppercase">Model Engine</div>
                <div className="text-white font-mono text-sm tracking-tighter">Gemini-2.0-Flash-Exp</div>
              </div>
            </div>
            
            <div className="glass rounded-3xl p-6 flex flex-col flex-1 min-h-[250px]">
              <h3 className="text-white/60 text-[11px] uppercase tracking-widest font-bold mb-4 shrink-0">Real-time Logs</h3>
              <div className="space-y-3 font-mono text-[10px] leading-tight overflow-y-auto pr-2" style={{ maxHeight: '200px' }}>
                <div className="flex gap-2 text-white/40">
                  <span className="text-cyan-600">[{formatTime(time)}]</span>
                  <span>Parsing UI Tree (82 nodes detected)</span>
                </div>
                <div className="flex gap-2 text-white/40">
                  <span className="text-cyan-600">[{formatTime(time)}]</span>
                  <span>Capturing display buffer (Display: 0)</span>
                </div>
                <div className="flex gap-2 text-emerald-500">
                  <span className="text-cyan-600">[{formatTime(time > 1 ? time - 1 : 0)}]</span>
                  <span>Gemini decision: TAP @ (480, 1220)</span>
                </div>
                <div className="flex gap-2 text-white/40">
                  <span className="text-cyan-600">[{formatTime(time > 1 ? time - 1 : 0)}]</span>
                  <span>Gesture dispatched: Stroke(0ms, 100ms)</span>
                </div>
                <div className="flex gap-2 text-yellow-500">
                  <span className="text-cyan-600">[{formatTime(time > 2 ? time - 2 : 0)}]</span>
                  <span>Waiting for window change event...</span>
                </div>
              </div>
            </div>
          </motion.div>
        </div>
      </div>
    </>
  );
}
