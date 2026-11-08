<template>
  <div class="register-page">
    <!-- 和登录页同一个品牌头，两个页面来回跳时视觉不跳戏 -->
    <div class="brand">
      <div class="brand-logo">Chat</div>
      <p class="brand-sub">注册后就能加好友聊天</p>
    </div>

    <el-card class="register-card chat-card" shadow="always">
      <template #header>
        <div class="card-title">注册账号</div>
      </template>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" clearable />
        </el-form-item>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入手机号" maxlength="11" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" show-password />
        </el-form-item>

        <el-form-item label="验证码" prop="code">
          <div class="code-row">
            <el-input v-model="form.code" placeholder="6 位验证码" maxlength="6" />
            <el-button class="code-btn" :disabled="countdown > 0" :loading="sendingCode" @click="onSendCode">
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
          <!-- 开发模式才出现：后端把验证码直接返回了（chat.sms.expose-code=true） -->
          <el-alert
            v-if="mockCode"
            class="mock-code-tip"
            type="warning"
            :closable="false"
            show-icon
            :title="`开发模式验证码：${mockCode}`"
            description="已自动填入。上线前请把 chat-auth 的 chat.sms.expose-code 改成 false"
          />
        </el-form-item>

        <el-button class="submit-btn" type="primary" native-type="submit" :loading="submitting">
          注 册
        </el-button>

        <div class="footer-link">
          已有账号？
          <span class="link" @click="goLogin">去登录</span>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { encryptCredentials, warmUpPublicKey } from '../utils/rsa'

const router = useRouter()

const formRef = ref()
const submitting = ref(false)
const sendingCode = ref(false)
const countdown = ref(0)
/** 开发模式下后端直返的验证码，仅用于界面提示；生产环境恒为空 */
const mockCode = ref('')
let timer = null

const form = reactive({
  username: '',
  phone: '',
  password: '',
  confirmPassword: '',
  code: '',
})

// ---- 校验规则 ----

const validateConfirm = (rule, value, callback) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

/**
 * 手机号唯一性：失焦时问后端。
 * 这样用户一离开输入框就知道被注册过了，而不是把整张表填完点注册才被告知。
 *
 * 注意：用户名【不做】唯一性校验 —— 用户名允许重复，它只是个显示名。
 *
 * api.checkPhone 已经把 Result<Boolean> 拆成了布尔值，
 * 所以这里直接判断 true/false，不用再写 res.data === false。
 */
const validatePhoneUnique = async (rule, value, callback) => {
  if (!value || !/^1[3-9]\d{9}$/.test(value)) return callback()
  try {
    const available = await api.checkPhone(value)
    callback(available ? undefined : new Error('该手机号已注册'))
  } catch {
    // 查重接口本身挂了不该阻塞用户填表 —— 真正的兜底是后端 createUser 的查重 + 唯一索引
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度 3~20 个字符', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
    { validator: validatePhoneUnique, trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6~20 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
}

// ---- 交互 ----

function goLogin() {
  router.push({ name: 'login' })
}

function startCountdown() {
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

/** 获取验证码：先校验手机号格式 + 是否已注册，再请求后端 */
async function onSendCode() {
  const phoneValid = await formRef.value.validateField('phone').then(() => true).catch(() => false)
  if (!phoneValid) return

  sendingCode.value = true
  try {
    // api.sendSmsCode 统一返回 { code }：
    // 开发环境（chat.sms.expose-code=true）后端把它填回来，生产环境是 null。
    const { code } = await api.sendSmsCode(form.phone)

    if (code) {
      mockCode.value = code
      form.code = code // 自动填入，省得手打
      ElMessage.success('验证码已自动填入')
    } else {
      ElMessage.success('验证码已发送')
    }
    startCountdown()
  } catch (e) {
    // ApiError.message 里有后端的原话，比如"请求过于频繁"、"手机号格式不正确"
    ElMessage.error(e?.message || '发送失败')
  } finally {
    sendingCode.value = false
  }
}

async function onSubmit() {
  if (submitting.value) return

  const valid = await formRef.value.validate().then(() => true).catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    // 密码 + 时间戳 + nonce 一起 RSA 加密（公钥从后端拉取）
    // 网络上只有密文，后端能校验"是否过期 / 是否被重放"
    //
    // 后端这个接口返回 Result<Void> —— 没有任何业务数据，
    // 所以 api.register 成功时返回 null，能走到下一行就说明注册成功了。
    await api.register({
      username: form.username,
      phone: form.phone,
      password: await encryptCredentials(form.password),
      code: form.code,
    })

    ElMessage.success('注册成功，请登录')
    router.push({ name: 'login' })
  } catch (e) {
    ElMessage.error(e?.message || '无法连接至服务器')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 提前把公钥拉下来，用户点注册时就不用等这一次请求了
  warmUpPublicKey()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.register-page {
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
  /* 和登录页逐字一致：两个页面的品牌区必须长得一样，改的时候要一起改。
     同样不用渐变字（理由见 Login.vue）—— 纯墨色大字 + 收紧字距。 */
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

.register-card {
  width: 380px;
  max-width: 100%;
  /* 同登录页：不投影，靠发丝线分层 */
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  text-align: center;
}

.code-row {
  display: flex;
  gap: 12px;
  width: 100%;
}

.code-btn {
  flex-shrink: 0;
  width: 120px;
}

.mock-code-tip {
  margin-top: 10px;
  width: 100%;
}

.submit-btn {
  width: 100%;
}

.footer-link {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
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
