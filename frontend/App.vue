<template>
  <div class="main-layout">
    <div class="content-card">
      <h1 class="title">🚀 极速图片压缩大师</h1>
      <p class="subtitle">输入期望 KB 值，精准压缩图片</p>
      <el-upload class="uploader" drag action="#" :auto-upload="false" :on-change="onFileChange" :limit="1" accept="image/*">
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">拖拽图片到此处 或 <em>点击上传</em></div>
      </el-upload>
      <div class="controls">
        <span class="label">期望大小 (KB):</span>
        <el-input-number v-model="targetKB" :min="10" :max="5120" />
        <el-button type="primary" size="large" :loading="loading" @click="handleUpload" class="btn-submit">立即极速压缩</el-button>
      </div>
    </div>
    <el-dialog v-model="dialogVisible" title="🎉 压缩成功！" width="400px" center>
      <div class="paywall-container">
        <p>目标: <b>{{ targetKB }}</b> KB | 实际: <b>{{ actualKB }}</b> KB</p>
        <div class="qr-placeholder"><p>微信收款码占位符</p><p style="font-size: 12px; color: #999">(静态图片示例)</p></div>
        <p class="pay-hint">MVP 测试阶段，请扫码支付 <b>9.9 元</b> 后点击下载</p>
        <el-button type="success" size="large" @click="downloadImage" class="btn-download">我已支付，下载图片</el-button>
      </div>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import axios from 'axios'
const targetKB = ref(200);const loading = ref(false);const selectedFile = ref(null);const dialogVisible = ref(false);const actualKB = ref(0);const fileUuid = ref('');
const onFileChange = (file) => { selectedFile.value = file.raw };
const handleUpload = async () => {
  if (!selectedFile.value) return alert('请先上传图片');loading.value = true;const formData = new FormData();formData.append('file', selectedFile.value);formData.append('targetKB', targetKB.value);
  try {
    const res = await axios.post('/api/compress', formData);actualKB.value = res.data.actualKB;fileUuid.value = res.data.uuid;dialogVisible.value = true;
  } catch (err) { alert('压缩失败，请重试') } finally { loading.value = false }
};
const downloadImage = () => { window.open(`/api/download/${fileUuid.value}`);dialogVisible.value = false };
</script>
<style scoped>
.main-layout { height: 100vh; display: flex; justify-content: center; align-items: center; background: #f0f2f5; font-family: 'Helvetica Neue', Helvetica, sans-serif; }
.content-card { background: white; padding: 40px; border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,0.05); width: 500px; text-align: center; }
.title { font-size: 28px; margin-bottom: 8px; color: #303133; }
.subtitle { color: #909399; margin-bottom: 30px; }
.uploader { margin-bottom: 20px; }
.controls { display: flex; flex-direction: column; align-items: center; gap: 20px; }
.label { font-size: 14px; color: #606266; }
.btn-submit { width: 100%; border-radius: 8px; }
.paywall-container { text-align: center; }
.qr-placeholder { background: #eee; height: 180px; width: 180px; margin: 20px auto; display: flex; flex-direction: column; justify-content: center; border: 1px dashed #ccc; }
.pay-hint { color: #f56c6c; margin: 15px 0; font-size: 14px; }
.btn-download { width: 100%; }
</style>