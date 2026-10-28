<script setup>
import { onMounted, onUnmounted, ref, watch } from "vue";
import { init, use } from "echarts/core";
import { LineChart, PieChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
use([
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
]);
const props = defineProps({ option: Object }),
  element = ref();
let chart, resize;
onMounted(() => {
  chart = init(element.value);
  if (props.option) chart.setOption({ ...props.option, animation: false });
  resize = new ResizeObserver(() => chart?.resize());
  resize.observe(element.value);
});
watch(
  () => props.option,
  (option) => {
    if (chart && option) chart.setOption({ ...option, animation: false }, true);
  },
  { deep: true },
);
onUnmounted(() => {
  resize?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div ref="element" style="width: 100%; height: 100%; min-height: 200px" />
</template>
