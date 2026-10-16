import {Capacitor,registerPlugin} from '@capacitor/core'
const feedback=registerPlugin<{alarm():Promise<void>}>('StationFeedback')
let context:AudioContext|undefined
export function unlockScannerAudio(){
  if(Capacitor.isNativePlatform())return
  try{context??=new AudioContext();if(context.state==='suspended')void context.resume().catch(()=>{})}catch{}
}
export async function scannerAlarm(){
  if(Capacitor.isNativePlatform()){try{await feedback.alarm()}catch{};return}
  unlockScannerAudio()
  if(!context||context.state!=='running')return
  for(const offset of [0,0.22]){
    const oscillator=context.createOscillator(),gain=context.createGain(),at=context.currentTime+offset
    oscillator.type='triangle';oscillator.frequency.value=880;oscillator.connect(gain);gain.connect(context.destination)
    gain.gain.setValueAtTime(0,at);gain.gain.linearRampToValueAtTime(0.22,at+0.01);gain.gain.exponentialRampToValueAtTime(0.001,at+0.17)
    oscillator.start(at);oscillator.stop(at+0.18);oscillator.onended=()=>{oscillator.disconnect();gain.disconnect()}
  }
}
export function closeScannerAudio(){void context?.close().catch(()=>{});context=undefined}
