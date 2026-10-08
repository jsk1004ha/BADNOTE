package com.inkforge.notesstudio

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdater(private val activity:Activity){
    data class Release(val version:String,val name:String,val url:String,val size:Long)
    fun latest():Release?{
        val connection=URL("https://api.github.com/repos/jsk1004ha/BADNOTE/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout=15000;connection.readTimeout=20000;connection.setRequestProperty("Accept","application/vnd.github+json")
        try{
            val data=connection.inputStream.bufferedReader().use{JSONObject(it.readText())}
            val version=data.optString("tag_name").removePrefix("v")
            fun versionCode(s:String)=s.split('.','-').take(3).map{it.toIntOrNull()?:0}.fold(0L){a,n->a*1000+n}
            if(versionCode(version)<=versionCode(BuildConfig.VERSION_NAME))return null
            val suffix=if(activity.packageName.startsWith("com.inkforge.note5"))"SideBySide.apk"else"Update.apk"
            val asset=data.array("assets").objects().firstOrNull{it.optString("name").endsWith(suffix)}?:return null
            return Release(version,asset.getString("name"),asset.getString("browser_download_url"),asset.optLong("size"))
        }finally{connection.disconnect()}
    }
    fun download(release:Release):File{
        require(release.url.startsWith("https://github.com/jsk1004ha/BADNOTE/releases/download/")){"업데이트 주소가 올바르지 않습니다."}
        val directory=File(activity.cacheDir,"updates").apply{mkdirs()};val file=File(directory,"badnote-update.apk")
        val connection=URL(release.url).openConnection()as HttpURLConnection;connection.connectTimeout=20000;connection.readTimeout=30000
        try{connection.inputStream.use{input->file.outputStream().use{ArchiveCodec.copy(input,it)}}
            require(release.size<=0||file.length()==release.size){"다운로드 크기가 일치하지 않습니다."}
            val info=requireNotNull(activity.packageManager.getPackageArchiveInfo(file.path,0)){"APK를 읽지 못했습니다."}
            require(info.packageName==activity.packageName){"다른 앱의 업데이트입니다."}
            return file
        }catch(e:Exception){file.delete();throw e}finally{connection.disconnect()}
    }
    fun install(file:File){
        if(Build.VERSION.SDK_INT>=26&&!activity.packageManager.canRequestPackageInstalls()){
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:${activity.packageName}")));return
        }
        val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.fileprovider",file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
