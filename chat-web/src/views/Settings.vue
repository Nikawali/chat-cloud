<template>
  <div class="settings-page chat-page">
    <h2 class="chat-title">设置</h2>

    <!-- ═══════════════ ① 我的资料 ═══════════════ -->
    <el-card class="chat-card">
      <template #header>
        <span class="card-title">我的资料</span>
      </template>

      <el-form :model="profileForm" label-width="76px" @submit.prevent>
        <el-form-item label="用户名">
          <div class="field">
            <!-- 只读，用 :model-value 而不是 v-model：它永远不该被改 -->
            <el-input :model-value="me?.username || ''" disabled />
            <p class="chat-muted">
              登录名不能改（改它等于换账号，要走验证新登录名的独立流程）。
              要改的是下面的昵称。
            </p>
          </div>
        </el-form-item>

        <el-form-item label="昵称">
          <div class="field">
            <el-input
              v-model="profileForm.nickName"
              maxlength="50"
              show-word-limit
              placeholder="别人看到的名字"
            />
          </div>
        </el-form-item>

        <el-form-item label="性别">
          <el-radio-group v-model="profileForm.gender">
            <!-- ⚠️ 用 value 而不是 label：Element Plus 2.6 起 label 改成"显示文案"了，
                 取值要用 value（这里文案写在插槽里，所以只有取值要传） -->
            <el-radio value="M">男</el-radio>
            <el-radio value="F">女</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="生日">
          <div class="field">
            <!-- value-format 直接给 "2000-01-01" 这种字符串：
                 后端字段是 LocalDate，传日期对象会被序列化成带时区的 ISO 串，反而对不上 -->
            <el-date-picker
              v-model="profileForm.birthDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择生日"
              :disabled-date="disableFuture"
            />
          </div>
        </el-form-item>

        <el-form-item label="头像">
          <div class="avatar-row">
            <!--
              预览优先显示"刚选的本地文件"（blob URL，立刻能看到效果），
              没有再退回服务端那份头像 —— 不做这一步的话，用户选完图什么变化都看不到，
              会以为没选上（上传是点了"保存"才发生的）。
            -->
            <el-avatar class="avatar-preview" :size="56" :src="avatarPreview">
              {{ avatarChar }}
            </el-avatar>
            <div class="field">
              <!--
                auto-upload=false：选文件只是"挑好"，真正的上传发生在点"保存"时 ——
                否则用户选了图就直接改了头像，没有反悔余地，也和表单的其它字段语义不一致。
                两个必填项（action / show-file-list）值得说明：
                  · action 在 auto-upload=false 时不会被请求，但 EP 的 prop 没有默认值，不给会警告；
                  · show-file-list=false：文件列表那一块长得和表单不搭，我们自己画预览。
              -->
              <el-upload
                action="#"
                :auto-upload="false"
                :show-file-list="false"
                accept="image/png,image/jpeg,image/gif,image/webp"
                :on-change="onAvatarPicked"
              >
                <el-button :icon="Upload">选择图片</el-button>
              </el-upload>
              <p class="chat-muted">
                支持 PNG / JPG / GIF / WEBP，不超过 2MB。选好后要点下面的「保存」才会上传。
              </p>
            </div>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="savingProfile" @click="saveProfile">
            保存
          </el-button>
          <el-button :disabled="savingProfile" @click="resetProfileForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ═══════════════ ② 修改密码 ═══════════════ -->
    <el-card class="chat-card">
      <template #header>
        <span class="card-title">修改密码</span>
      </template>

      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="76px" @submit.prevent>
        <el-form-item label="手机号" prop="phone">
          <div class="field">
            <el-input v-model="pwdForm.phone" maxlength="11" placeholder="当前账号绑定的手机号" />
            <p class="chat-muted">
              资料接口故意不返回手机号（敏感字段），所以要你自己填 —— 后端会核对它是不是本账号的。
            </p>
          </div>
        </el-form-item>

        <el-form-item label="验证码" prop="code">
          <div class="code-row">
            <el-input v-model="pwdForm.code" maxlength="6" placeholder="6 位数字" />
            <el-button
              class="code-btn"
              :disabled="countdown > 0"
              :loading="sendingCode"
              @click="onSendCode"
            >
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="新密码" prop="password">
          <el-input
            v-model="pwdForm.password"
            type="password"
            show-password
            maxlength="20"
            placeholder="6~20 位"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            show-password
            maxlength="20"
            placeholder="再输一遍新密码"
          />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="changingPassword" @click="submitPassword">
            修改密码
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ═══════════════ ③ 模式选择 ═══════════════ -->
    <el-card class="chat-card">
      <template #header>
        <span class="card-title">模式选择</span>
      </template>

      <el-radio-group v-model="themeMode" @change="onThemeChange">
        <el-radio-button
          v-for="mode in THEME_MODES"
          :key="mode.value"
          :value="mode.value"
        >
          {{ mode.label }}
        </el-radio-button>
      </el-radio-group>
      <p class="chat-muted">
        「跟随系统」会跟着系统的深浅色自动切换。选择会记住，下次启动直接生效。
      </p>
    </el-card>

    <!-- ═══════════════ ④ 退出登录 ═══════════════ -->
    <el-card class="chat-card">
      <template #header>
        <span class="card-title">退出登录</span>
      </template>

      <el-button type="danger" plain :loading="loggingOut" @click="onLogout">退出登录</el-button>
      <p class="chat-muted">
        退出后要重新输入密码。本地聊天记录<b>不会</b>被删掉，它按账号分开存放
        —— 换别的账号登录看不到上一个人的记录，原来的人再登回来还在。
      </p>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 设置页：我的资料 / 修改密码 / 模式选择 / 退出登录。
 *
 * <h3>这几块的共同点：都只在自己这一页做，不缓存、不联动</h3>
 * 资料改完直接用后端返回的最新资料刷新本页表单（{@code PUT /user/profile} 会返回改完的
 * {@code UserDetailVO}），不再多查一次；密码改完只清空输入框。别的页面（联系人、聊天页）
 * 显示的还是它们自己之前拉到的名字/头像 —— 这是"每个页面各拉各的"这个架构的必然结果，
 * 不在这里做全局广播（那需要一套状态库，属于下一步的事）。
 *
 * <h3>两个"做不到"，都写在对应位置，避免以后当成 bug 查</h3>
 * <ol>
 *   <li><b>清空某一项做不到</b>：后端 {@code UpdateProfileRequest} 把所有字段都定义成
 *       "可选，null/空串 = 这一项不改"（{@code UserServiceImpl#updateProfile} 里
 *       {@code trimToNull} 之后交给 MyBatis-Plus 跳过该列）。所以"留空"在这里的含义是
 *       <b>不改</b>，不是"删掉"。要支持清空，得后端先有"显式清空"的语义。</li>
 *   <li><b>手机号拿不到</b>：{@code UserDetailVO} 故意不含 phone（敏感字段），
 *       所以修改密码那块的手机号必须用户自己填。</li>
 * </ol>
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, assetUrl } from '../api'
import { me } from '../utils/accountData'
import { firstChar } from '../utils/display'
import { performLogout } from '../utils/logout'
import { encryptCredentials } from '../utils/rsa'
import { THEME_MODES, getThemeMode, setThemeMode } from '../utils/theme'
import { Upload } from '@element-plus/icons-vue'

const router = useRouter()

// ==================== 我的资料 ====================

/**
 * 当前登录用户的资料（{@code UserDetailVO}）—— 来自账号级缓存
 * （{@code utils/accountData.js}，登录时拉好）。用来①显示用户名 ②算"改了哪些字段"。
 * 保存成功后 {@code saveProfile} 会把这个共享 ref 更新，别的页面随之同步。
 */
const savingProfile = ref(false)

/** 表单里全用字符串/null 起手（输入框要的是字符串，日期选择器给的是字符串） */
const profileForm = reactive({
  nickName: '',
  gender: '',
  birthDate: null,
  avatarUrl: '',
})

/** 头像没有图时显示名字首字（和会话列表、联系人页同一套规则，见 utils/display） */
const avatarChar = computed(() => firstChar(profileForm.nickName || me.value?.username || ''))

/** 生日不能选未来 —— 和后端 {@code @Past} 的校验保持一致，免得提交了才被拒 */
function disableFuture(date) {
  return date.getTime() > Date.now()
}

/** 把一份资料填进表单。加载完成和保存成功之后都要调，规则只有这一份 */
function fillProfileForm(data) {
  profileForm.nickName = data?.nickName || ''
  profileForm.gender = data?.gender || ''
  profileForm.birthDate = data?.birthDate || null
  profileForm.avatarUrl = data?.avatarUrl || ''
}

function resetProfileForm() {
  fillProfileForm(me.value)
  // 顺便把"已经选好但还没上传"的图也撤掉 —— 不然点了重置，预览还停在新图上
  clearPickedAvatar()
}

// ---- 头像：选图（本地预览）与上传 ----

/** 选好但【还没上传】的文件。保存时才发给后端 */
const avatarFile = ref(null)

/**
 * 本地预览用的 blob URL。
 *
 * <p>为什么需要它：选完图到点"保存"之间，图片还没上传，服务端那份头像还是旧的；
 * 不给即时预览的话，用户选完图什么都看不到，会以为没选上。
 *
 * <p>⚠️ 用完必须 revoke：blob URL 会让浏览器一直拿着那份文件数据
 * （换一张图、保存成功、离开页面，三处都要清）。
 */
const avatarObjectUrl = ref(null)

/** 预览地址：优先"刚选的本地文件"，否则是服务端那份（相对路径要拼 API 基址） */
const avatarPreview = computed(() => avatarObjectUrl.value || assetUrl(profileForm.avatarUrl))

function clearPickedAvatar() {
  avatarFile.value = null
  if (avatarObjectUrl.value) {
    URL.revokeObjectURL(avatarObjectUrl.value)
    avatarObjectUrl.value = null
  }
}

/**
 * el-upload 选中文件时调（auto-upload=false，所以这里不会发请求）。
 *
 * <p>前端先按类型和大小拦一道，理由不是为了省那一次请求，而是<b>反馈要快</b>：
 * 等后端返回 400 再提示，用户已经盯着转圈等了一个来回。
 * 后端那道校验依然必须留着 —— 前端校验防的是手滑，不是坏人。
 */
function onAvatarPicked(uploadFile) {
  const file = uploadFile?.raw
  if (!file) {
    return
  }
  if (!/^image\/(png|jpeg|gif|webp)$/.test(file.type)) {
    ElMessage.error('只支持 PNG/JPG/GIF/WEBP')
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.error('图片不能超过 2MB')
    return
  }
  // 换一张先撤掉上一张的 blob，否则换十次就攒十个不释放的对象
  clearPickedAvatar()
  avatarFile.value = file
  avatarObjectUrl.value = URL.createObjectURL(file)
}

/**
 * 只提交<b>改动过的</b>字段（+ 可能的新头像）。
 *
 * <p>两个理由：① 后端是全字段可选，一个都不传会直接回 400「没有需要更新的字段」；
 * ② 只传改动项，服务端日志里一眼能看出这次到底改了什么。
 *
 * <p>比较时用 {@code || ''} / {@code || null} 把 undefined 归一化：
 * 接口的字段可能缺省，直接比会让"没这个字段"和"字段是空串"判成两次改动。
 *
 * <p>头像不参与这个"字段比较"：它比较的是文件（选了新图就是有改动），
 * 而且上传后由服务端生成 URL —— 前端不该猜那个地址长什么样。
 */
async function saveProfile() {
  if (savingProfile.value) {
    return
  }
  const payload = {}
  if (profileForm.nickName !== (me.value?.nickName || '')) {
    payload.nickName = profileForm.nickName
  }
  if (profileForm.gender !== (me.value?.gender || '')) {
    payload.gender = profileForm.gender
  }
  if (profileForm.birthDate !== (me.value?.birthDate || null)) {
    payload.birthDate = profileForm.birthDate
  }

  const hasNewAvatar = !!avatarFile.value
  if (Object.keys(payload).length === 0 && !hasNewAvatar) {
    // 提前拦下来：不然会拿到一个 400，用户看着像"保存失败"，其实是他没改任何东西
    ElMessage.info('没有改动')
    return
  }

  savingProfile.value = true
  try {
    // 接口返回改完之后的完整资料，直接拿去刷新界面，不用再查一次。
    // 第二个参数是文件：传了就按 multipart 上传，没传就是纯 JSON 改字段。
    me.value = await api.updateProfile(payload, avatarFile.value)
    // 上传成功 → 撤掉本地预览：之后显示的是服务端那份（新头像的相对路径）
    clearPickedAvatar()
    // 回填：空串会被后端 trimToNull 当成"不改"，所以以服务端返回的值为准，
    // 免得界面上留着用户输入的空白，看起来像改成功了
    fillProfileForm(me.value)
    ElMessage.success(hasNewAvatar ? '头像已更新' : '已保存')
  } catch (e) {
    // 失败时【故意保留】选好的文件和预览：用户多半是图太大或格式不对，
    // 重新选一遍很烦；留着让他能直接换个文件再点保存
    ElMessage.error(e?.message || '保存失败')
  } finally {
    savingProfile.value = false
  }
}

// ==================== 修改密码 ====================

const pwdFormRef = ref()
const pwdForm = reactive({
  phone: '',
  code: '',
  password: '',
  confirmPassword: '',
})
const sendingCode = ref(false)
const changingPassword = ref(false)

/** 验证码倒计时（秒）。和注册页同一套规则：60 秒内不能重复发 */
const countdown = ref(0)
let countdownTimer = null

function validateConfirm(rule, value, callback) {
  if (!value) {
    callback(new Error('请再次输入密码'))
  } else if (value !== pwdForm.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const pwdRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6~20 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
}

function startCountdown() {
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      clearInterval(countdownTimer)
      countdownTimer = null
    }
  }, 1000)
}

/**
 * 发验证码。
 *
 * <p>复用注册那套发码接口（{@code POST /auth/sms-code}）：验证码本身不区分用途，
 * 后端按手机号发、按手机号存，改密码和注册的差别只在"提交时带不带新密码"。
 *
 * <p>开发环境下后端会把验证码回填在响应里（{@code chat.sms.expose-code=true}），
 * 这里顺手自动填入，省得去翻日志。
 */
async function onSendCode() {
  const phoneValid = await pwdFormRef.value
    .validateField('phone')
    .then(() => true)
    .catch(() => false)
  if (!phoneValid) {
    return
  }

  sendingCode.value = true
  try {
    const { code } = await api.sendSmsCode(pwdForm.phone)
    if (code) {
      pwdForm.code = code
      ElMessage.success('验证码已自动填入')
    } else {
      ElMessage.success('验证码已发送')
    }
    startCountdown()
  } catch (e) {
    ElMessage.error(e?.message || '发送失败')
  } finally {
    sendingCode.value = false
  }
}

/**
 * 提交改密码。
 *
 * <p>新密码走和登录/注册完全相同的加密：{@code 时间戳|nonce|新密码} 一起 RSA 加密，
 * 网络上只有密文，后端能校验"是否过期 / 是否被重放"。
 * 明文密码只在这一个函数的内存里出现过。
 */
async function submitPassword() {
  if (changingPassword.value) {
    return
  }
  const valid = await pwdFormRef.value.validate().then(() => true).catch(() => false)
  if (!valid) {
    return
  }

  changingPassword.value = true
  try {
    await api.changePassword({
      phone: pwdForm.phone,
      code: pwdForm.code,
      password: await encryptCredentials(pwdForm.password),
    })
    ElMessage.success('密码已修改')
    // 只清密码相关的输入，手机号留着 —— 用户可能还要再改一次
    pwdForm.code = ''
    pwdForm.password = ''
    pwdForm.confirmPassword = ''
    pwdFormRef.value.clearValidate(['code', 'password', 'confirmPassword'])
  } catch (e) {
    ElMessage.error(e?.message || '修改失败')
  } finally {
    changingPassword.value = false
  }
}

// ==================== 模式选择 ====================

/** 初始值从本地偏好读（utils/theme 里做的归一化，非法值会退回"跟随系统"） */
const themeMode = ref(getThemeMode())

/**
 * 切换主题。
 *
 * <p>{@link setThemeMode} 里"存偏好 + 应用到 DOM + 重挂系统监听"是一起做的，
 * 所以这里不需要再自己碰 DOM（主题的实现在 utils/theme.js，只此一份）。
 */
function onThemeChange(mode) {
  setThemeMode(mode)
}

// ==================== 退出登录 ====================

const loggingOut = ref(false)

async function onLogout() {
  try {
    await ElMessageBox.confirm(
      '退出后需要重新输入密码才能登录。本地聊天记录会留在本机，不会被删掉。',
      '退出登录',
      { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' },
    )
  } catch {
    // ElMessageBox 用 reject 表达"用户点了取消"，这不是错误
    return
  }

  loggingOut.value = true
  try {
    // 清理序列（通知后端 + 断连接 + 停同步 + 清本地身份 + 清 token）在 utils/logout.js，
    // 登录页用的是同一个 —— 少做一步就是能看见的 bug（详见那里的注释）
    const { serverLogoutOk } = await performLogout()
    if (serverLogoutOk) {
      ElMessage.success('已退出登录')
    } else {
      // 后端没响应时本地照样清干净了，只是服务端的 token 要等它自己过期 —— 一句话说清
      ElMessage.warning('已退出（服务端未响应）')
    }
    router.push({ name: 'login' })
  } finally {
    loggingOut.value = false
  }
}

// ==================== 生命周期 ====================

// 资料来自账号缓存：挂载时可能已经好了（immediate 立刻填一次），
// 也可能晚一点才到（watch 到了再填）。不再为进这一页发一次 /user/me。
watch(me, (value) => {
  if (value) {
    fillProfileForm(value)
  }
}, { immediate: true })

onBeforeUnmount(() => {
  // 定时器不清的话，页面已经卸载了它还在跑（而且会去改一个已经不存在的组件状态）
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
  // blob 预览地址也要释放：它是浏览器替我们持有的那份文件数据，不 revoke 就回收不掉
  clearPickedAvatar()
})
</script>

<style scoped>
/*
  这里【故意没有】.settings-page 和 .hint 的样式：
  · 页面外壳（padding + 竖排间距）用全局的 .chat-page —— 三个 tab 页共用一份，改了全站一致；
  · 次要说明文字用全局的 .chat-muted。
  本页只留"只有这一页才有"的东西（下面这些）。
*/

.card-title {
  font-weight: 600;
}

/*
  el-form-item 的内容区是 flex 行，直接往里塞 <input> + <p> 会让提示文字跑到输入框右边。
  所以每条都包一层 .field（宽度占满），提示才会落到输入框下面。
*/
.field {
  width: 100%;
  /* flex 子项默认 min-width:auto，里面的输入框有最小宽度，会把所在行撑破 —— 显式允许收缩 */
  min-width: 0;
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}

/*
  头像预览：加一圈发丝线。
  没设头像时 el-avatar 显示的是首字（浅色底），有描边才能看出"这是一块头像位"，
  否则它和页面底色糊在一起。
*/
.avatar-preview {
  flex-shrink: 0;
  border: 1px solid var(--chat-border);
}

.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

/* 按钮不要被输入框挤扁（flex 子项默认会被压缩） */
.code-row .code-btn {
  flex-shrink: 0;
}
</style>
