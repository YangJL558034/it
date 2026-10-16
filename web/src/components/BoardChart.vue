<script setup lang="ts">
import {onMounted,onUnmounted,ref,watch} from 'vue'
import {use,init,type EChartsCoreOption,type ECharts} from 'echarts/core'
import {LineChart,PieChart,BarChart} from 'echarts/charts'
import {GridComponent,TooltipComponent,LegendComponent} from 'echarts/components'
import {CanvasRenderer} from 'echarts/renderers'
use([LineChart,PieChart,BarChart,GridComponent,TooltipComponent,LegendComponent,CanvasRenderer])
const props=defineProps<{option:EChartsCoreOption;label:string}>()
const root=ref<HTMLDivElement>();let chart:ECharts|undefined,observer:ResizeObserver|undefined
onMounted(()=>{chart=init(root.value!,undefined,{renderer:'canvas'});chart.setOption(props.option);observer=new ResizeObserver(()=>chart?.resize());observer.observe(root.value!)})
watch(()=>props.option,option=>chart?.setOption(option,{notMerge:true}))
onUnmounted(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="root" class="board-chart" role="img" :aria-label="label"></div></template>
