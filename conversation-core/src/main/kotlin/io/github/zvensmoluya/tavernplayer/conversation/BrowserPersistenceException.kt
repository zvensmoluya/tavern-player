package io.github.zvensmoluya.tavernplayer.conversation

class BrowserPersistenceException(cause: Exception) : IllegalStateException("网页状态保存失败，当前运行实例已停止，请重新加载", cause)
