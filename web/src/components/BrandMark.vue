<script setup lang="ts">
import { branding } from "../branding";
</script>
<template>
  <span class="platform-brand"
    ><img :src="branding.logo" alt="平台 Logo" /><b>{{
      branding.name
    }}</b></span
  >
</template>
<style scoped>
.platform-brand {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  color: inherit;
  font-weight: 650;
}
.platform-brand img {
  width: 38px;
  height: 38px;
  object-fit: contain;
  flex: none;
}
</style>
