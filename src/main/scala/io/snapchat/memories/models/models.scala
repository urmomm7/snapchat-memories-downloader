package io.snapchat.memories

package models {

  case class NoMemoriesFileError(message: String) extends Exception(message)

  case class SetMediaTimeError(message: String, t: Throwable) extends Exception(message, t)

  case class DownloadError(message: String) extends Exception(message)

  case class JsonError(message: String, t: Throwable) extends Exception(message)

}