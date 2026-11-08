<template>
  <div class="login-page">
    <!--
      品牌头：登录页是用户见到的第一屏，只有一张光秃秃的表单会显得"还没做完"。
      加一个标识 + 一句说明，卡片本身就能矮一点，视觉上有了主次。
    -->
    <div class="brand">
      <div class="brand-logo">Chat</div>
      <p class="brand-sub">登录后即可收发消息</p>
    </div>

    <el-card class="login-card chat-card" shadow="always">
      <template #header>
        <div class="card-title">登录</div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" maxlength="11" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-button class="submit-btn" type="primary" native-type="submit" :loading="submitting">
          登 录
        </el-button>

        <div class="footer-link">
          还没有账号？
          <span class="link" @click="goRegister">立即注册</span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 登录页。
 *
 * <p><b>只管"输账号密码 → 拿 token → 进主页"</b>。原来这里还有一块"已登录"卡片
 * （显示 token、到期时间、存放位置、复制 token、退出登录），那是开发期用来验证登录态的，
 * 属于调试界面：登录成功后应当直接进主页，而不是停在这看一张 token 表。
 * 那些能力现在都有正经入口 —— 退出登录在「设置」页，登录态由路由守卫处理。
 *
 * <p>登录成功后要做三件事，顺序不能乱（详见 onSubmit 里的注释）：
 * 存凭证 → **等凭证落盘** → 连实时通道 + 启动全局同步。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { encryptCredentials } from '../utils/rsa'
import { saveToken, consumeExpiredFlag, flushCredentialWrite } from '../utils/auth'
import { loadIdentity } from '../utils/accountData'
import { restartChatConnection } from '../utils/chatSocket'
import { startChatSync } from '../utils/chatSync'

const router = useRouter()

const formRef = ref()
const submitting = ref(false)

const form = reactive({
  phone: '',
  password: '',
})

const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6~20 位', trigger: 'blur' },
  ],
}

function goRegister() {
  router.push({ name: 'register' })
}

async function onSubmit() {
  if (submitting.value) {
    return
  }

  const valid = await formRef.value.validate().then(() => true).catch(() => false)
  if (!valid) {
    return
  }

  submitting.value = true
  try {
    /*
      密码 + 时间戳 + nonce 一起 RSA 加密（公钥从后端拉取）。
      api.login 已经把 Result 外壳拆掉了：成功时它返回的就是 token 字符串本身，
      失败时会抛 ApiError（带 code），所以这里不需要再判断 code。
    */
    const token = await api.login({
      phone: form.phone,
      password: await encryptCredentials(form.password),
    })

    if (!token) {
      // 后端返回成功却没有 token，属于不该发生的情况，明确报出来而不是静默"已登录"
      throw new Error('登录响应缺少 token，请检查后端接口')
    }

    saveToken(token)

    /*
      ⚠️ 必须等凭证真正落盘，再去连实时通道。
      主进程建 WS 时要【同步读】credentials.json 拿 token，而 saveToken 的写盘 IPC
      是不等待的。少了这一行，主进程会读到旧文件 → 报 no-token 并放弃连接，
      而页面已经显示"登录成功" —— 结果就是实时通道一直不通、消息只能靠补拉
      （这正是"必须聚焦才能收到消息"的根因）。
    */
    await flushCredentialWrite()

    // 实时通道要换一条新连接：握手带的是"当时那个 token"，
    // 而重新登录之后旧 token 已经被服务端删掉了 —— 旧连接代表的是失效的身份。
    restartChatConnection()
    // 启动全局消息同步（收消息 + 回 ack + 补拉）。
    // 它内部会确保连接已建立，所以放在 restartChatConnection 之后。
    startChatSync()
    // 账号维度的内存缓存（我是谁 / 好友 / 申请）拉一次，之后各页只读缓存、切页 0 请求。
    // 不 await：登录要快，这几份数据是响应式的，回来自己会把名字/头像补上。
    loadIdentity()

    ElMessage.success('登录成功')
    // 直接进主页（消息页）。不再停留展示 token —— 那是调试界面。
    router.push({ name: 'message' })
  } catch (e) {
    // ApiError 的 message 是后端的原话（"手机号或密码错误""账号已锁定"……）
    ElMessage.error(e?.message || '登录失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 上次是因为凭证过期被登出的（getToken 里检测到并打了标记），这里提示一次
  if (consumeExpiredFlag()) {
    ElMessage.warning('登录已过期，请重新登录')
  }
})
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  /* 竖排 + 居中：品牌头在卡片上方，所以不能再用单一的 align-items:center */
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 22px;
  padding: 24px;
}

.brand {
  text-align: center;
}

.brand-logo {
  /*
    不用渐变文字。
    渐变字（background-clip: text）是"AI 生成感"最重的写法之一：它不承载任何信息，
    只是想让页面"看起来花了心思"。方向 A 用纯墨色大字 + 收紧的字距，安静但更像正经产品。
  */
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--chat-text);
}

.brand-sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--chat-text-3);
}

.login-card {
  width: 380px;
  max-width: 100%;
  /* 不投影：方向 A 里卡片靠发丝线分层，投影只留给真正的浮层（对话框/下拉） */
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  text-align: center;
}

.submit-btn {
  width: 100%;
}

.footer-link {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  color: var(--chat-text-3);
}

.link {
  color: var(--chat-brand);
  cursor: pointer;
}

.link:hover {
  text-decoration: underline;
}
</style>
