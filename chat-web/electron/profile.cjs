// "多开 profile" 的名字解析。
//
// 刻意【不依赖 electron】：只做"从命令行/环境变量里取一个安全的名字"这件事，
// 这样它能用普通 node 直接跑测试（electron 的模块离了 Electron 运行时就 require 不进来）。
const path = require('node:path')

/** 命令行形式：electron . --profile=alice */
const ARG_PREFIX = '--profile='
/** 只允许字母数字和 - _：这个名字会直接当目录名用，不能让 ".." 之类的东西进来 */
const UNSAFE = /[^A-Za-z0-9_-]/g
/** 够用就行，顺手挡住超长名字 */
const MAX_LENGTH = 32

/**
 * 取 profile 名。
 *
 * @param {string[]} [argv] 默认 process.argv（命令行优先）
 * @param {object}   [env]  默认 process.env（其次看 CHAT_PROFILE）
 * @returns {string} 安全化之后的名字；<b>空串表示不启用 profile</b>（=保持原来的单账号行为）
 */
function resolveProfile(argv = process.argv, env = process.env) {
  const fromArg = (argv || []).find(
    (arg) => typeof arg === 'string' && arg.startsWith(ARG_PREFIX),
  )
  const raw = (fromArg ? fromArg.slice(ARG_PREFIX.length) : '') || env?.CHAT_PROFILE || ''
  return String(raw).trim().replace(UNSAFE, '').slice(0, MAX_LENGTH)
}

/**
 * 算出某个 profile 的数据目录。
 *
 * <p>放在默认 userData 目录【里面】的 profiles/ 子目录下，而不是另起一个不相干的目录：
 * 这样所有账号的数据还是聚在一个地方，想重置某个账号直接删它那个子目录就行。
 *
 * @param {string} baseUserDataDir Electron 的默认 userData 路径
 * @param {string} profile 已经过 {@link resolveProfile} 安全化的名字
 */
function profileDir(baseUserDataDir, profile) {
  return path.join(baseUserDataDir, 'profiles', profile)
}

module.exports = { resolveProfile, profileDir }
