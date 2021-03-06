package lib

import java.io.File

import com.gu.mediaservice.lib.cleanup.{ComposedImageProcessor, ImageProcessor}
import com.gu.mediaservice.lib.config.{CommonConfig, ImageProcessorLoader}
import com.gu.mediaservice.model._
import com.typesafe.scalalogging.StrictLogging
import play.api.Configuration

class ImageLoaderConfig(playAppConfiguration: Configuration) extends CommonConfig(playAppConfiguration) with StrictLogging {
  val imageBucket: String = string("s3.image.bucket")

  val thumbnailBucket: String = string("s3.thumb.bucket")

  val tempDir: File = new File(stringDefault("upload.tmp.dir", "/tmp"))

  val thumbWidth: Int = 256
  val thumbQuality: Double = 85d // out of 100

  val rootUri: String = services.loaderBaseUri
  val apiUri: String = services.apiBaseUri
  val loginUriTemplate: String = services.loginUriTemplate

  val transcodedMimeTypes: List[MimeType] = getStringSet("transcoded.mime.types").toList.map(MimeType(_))
  val supportedMimeTypes: List[MimeType] = List(Jpeg, Png) ::: transcodedMimeTypes

  /**
    * Load in the chain of image processors from config. This can be a list of
    * companion objects, class names, both with and without config.
    * For example:
    * {{{
    * image.processors = [
    *   // simple class
    *   "com.gu.mediaservice.lib.cleanup.GuardianMetadataCleaners",
    *   // a companion object
    *   "com.gu.mediaservice.lib.cleanup.SupplierProcessors$",
    *   "com.yourdomain.YourImageProcessor",
    *   // a class with a single arg constructor taking a play Configuration object
    *   {
    *     className: "com.yourdomain.YourImageProcessorWithConfig"
    *     config: {
    *       configKey1: value1
    *     }
    *   }
    * ]
    * }}}
    *
    * Depending on the type it will be loaded differently using reflection. Companion objects will be looked up
    * and the singleton instance added to the list. Classes will be looked up and will be examined for an appropriate
    * constructor. The constructor can either be no-arg or have a single argument of `play.api.Configuration`.
    *
    * If configuration is specified but not used (a companion object or class with no arg constructor is specified)
    * then loading the image processor will fail so as to avoid configuration errors.
    */
  val imageProcessor: ComposedImageProcessor = {
    val processors = configuration
      .get[Seq[ImageProcessor]]("image.processors")(ImageProcessorLoader.imageProcessorsConfigLoader)
    ImageProcessor.compose("ImageConfigLoader-imageProcessor", processors:_*)
  }
}
