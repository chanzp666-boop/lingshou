package com.byd.ls.shell

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri

/**
 * 只给 BYD framework 设备类用的 Context。
 *
 * 背景：`android.hardware.bydauto.*Device` 的每个 getter 方法体第一行就是
 * `mContext.enforceCallingOrSelfPermission("...BYDAUTO_*_GET", ...)`（见 helper/src/.../HelperMain.java
 * 的 404/438 注释：权限检查只写在 getter 里）。这个 mContext 就是 getInstance(ctx) 传进去的那一个，
 * 于是把检查压在本地：换成本类之后 getter 的权限断言直接通过，后面真正的取值才走 binder。
 *
 * 与 wheregoes/byd-apps 的 BydPermissionContext 同一思路（api-ref.md:19-68 的三档权限模型）。
 * 区别：他们用反射/动态代理包 Context，我们直接继承 ContextWrapper（本类只需覆盖权限方法）。
 *
 * 边界：
 * - 只影响**客户端本地**的权限断言；服务端（system_server / libbydautoservice.so）若按调用 uid 再校验一次签名权限，
 *   这里挡不住，getter 仍会抛 SecurityException —— 那就说明这条路在本固件不成立（用 byd.compare 一次就能定论）。
 * - 不要用它去做 `checkSelfPermission` 之外的危险事：所有转发都回到 base，只有权限判定被放行。
 */
class BydPermissionContext(base: Context) : ContextWrapper(base) {

    private fun granted(): Int = PackageManager.PERMISSION_GRANTED

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = granted()
    override fun checkCallingPermission(permission: String): Int = granted()
    override fun checkCallingOrSelfPermission(permission: String): Int = granted()
    override fun checkUriPermission(uri: Uri?, pid: Int, uid: Int, modeFlags: Int): Int = granted()
    override fun checkCallingUriPermission(uri: Uri?, modeFlags: Int): Int = granted()
    override fun checkCallingOrSelfUriPermission(uri: Uri?, modeFlags: Int): Int = granted()
    override fun checkUriPermission(
        uri: Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int
    ): Int = granted()

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) { /* 放行 */ }
    override fun enforceCallingPermission(permission: String, message: String?) { /* 放行 */ }
    override fun enforceCallingOrSelfPermission(permission: String, message: String?) { /* 放行 */ }
    override fun enforceUriPermission(uri: Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) { }
    override fun enforceCallingUriPermission(uri: Uri?, modeFlags: Int, message: String?) { }
    override fun enforceCallingOrSelfUriPermission(uri: Uri?, modeFlags: Int, message: String?) { }
    override fun enforceUriPermission(
        uri: Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int, message: String?
    ) { }
}
