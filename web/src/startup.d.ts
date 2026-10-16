export {}
declare global {
  interface Window { mesStartup?: { ready():void; fail(message:string):void } }
}
