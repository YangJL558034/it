import type {CapacitorConfig} from '@capacitor/cli'
const config:CapacitorConfig={appId:'com.factory.mes',appName:'智造 MES',webDir:'build',server:{androidScheme:'https'},android:{allowMixedContent:true},plugins:{CapacitorHttp:{enabled:true}}}
export default config
