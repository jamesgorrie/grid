package com.gu.mediaservice.lib.auth.provider

import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.argo.model.Link
import com.gu.mediaservice.lib.auth.provider.Authentication.{ApiKeyAccessor, OnBehalfOfPrincipal, PandaUser, Principal}
import com.gu.mediaservice.lib.auth.{ApiAccessor, Internal}
import com.gu.mediaservice.lib.config.CommonConfig
import com.gu.pandomainauth.model.{AuthenticatedUser, User}
import com.gu.pandomainauth.service.Google2FAGroupChecker
import play.api.libs.ws.WSRequest
import play.api.mvc.Security.AuthenticatedRequest
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

class Authentication(config: CommonConfig,
                     providers: AuthenticationProviders,
                     override val parser: BodyParser[AnyContent],
                     override val executionContext: ExecutionContext)
  extends ActionBuilder[Authentication.Request, AnyContent] with ArgoHelpers {

  // make the execution context implicit so it will be picked up appropriately
  implicit val ec: ExecutionContext = executionContext

  val loginLinks = List(
    Link("login", config.services.loginUriTemplate)
  )

  def unauthorised(errorMessage: String, throwable: Option[Throwable] = None): Future[Result] = {
    logger.info(s"Authentication failure $errorMessage", throwable.orNull)
    Future.successful(respondError(Unauthorized, "authentication-failure", "Authentication failure", loginLinks))
  }

  def forbidden(errorMessage: String): Future[Result] = {
    logger.info(s"User not authorised: $errorMessage")
    Future.successful(respondError(Forbidden, "principal-not-authorised", "Principal not authorised", loginLinks))
  }

  def authenticationStatus(requestHeader: RequestHeader, providers: AuthenticationProviders) = {
    def sendForAuth(maybePrincipal: Option[Principal]): Future[Result] = {
      providers.userProvider.sendForAuthentication.fold(unauthorised("No path to authenticate user"))(_(requestHeader, maybePrincipal))
    }

    def flushToken(resultWhenAbsent: Result): Result = {
      providers.userProvider.flushToken.fold(resultWhenAbsent)(_(requestHeader))
    }

    providers.apiProvider.authenticateRequest(requestHeader) match {
      case Authenticated(authedUser) => Right(authedUser)
      case Invalid(message, throwable) => Left(unauthorised(message, throwable))
      case NotAuthorised(message) => Left(forbidden(s"Principal not authorised: $message"))
      case NotAuthenticated =>
        providers.userProvider.authenticateRequest(requestHeader) match {
          case NotAuthenticated => Left(sendForAuth(None))
          case Expired(principal) => Left(sendForAuth(Some(principal)))
          case GracePeriod(authedUser) => Right(authedUser)
          case Authenticated(authedUser) => Right(authedUser)
          case Invalid(message, throwable) => Left(unauthorised(message, throwable).map(flushToken))
          case NotAuthorised(message) => Left(forbidden(s"Principal not authorised: $message"))
        }
    }
  }

  override def invokeBlock[A](request: Request[A], block: Authentication.Request[A] => Future[Result]): Future[Result] = {
    // Authenticate request. Try with API authenticator first and then with user authenticator
    authenticationStatus(request, providers) match {
      // we have a principal, so process the block
      case Right(principal) => block(new AuthenticatedRequest(principal, request))
      // no principal so return a result which will either be an error or a form of redirect
      case Left(result) => result
    }
  }

  def getOnBehalfOfPrincipal(principal: Principal, originalRequest: RequestHeader): OnBehalfOfPrincipal = {
    def failureToException[T](result: Either[String, T]): T = result.fold(error => throw new IllegalStateException(error), identity)
    val enrichWithAuth = principal match {
      case _:ApiKeyAccessor => providers.apiProvider.onBehalfOf(originalRequest) andThen failureToException
      case _:PandaUser      => providers.userProvider.onBehalfOf(originalRequest) andThen failureToException
    }
    new OnBehalfOfPrincipal {
      override def enrich: WSRequest => WSRequest = enrichWithAuth
    }
  }
}

object Authentication {
  sealed trait Principal {
    def accessor: ApiAccessor
  }
  case class PandaUser(user: User) extends Principal {
    def accessor: ApiAccessor = ApiAccessor(identity = user.email, tier = Internal)
  }
  case class ApiKeyAccessor(accessor: ApiAccessor) extends Principal

  type Request[A] = AuthenticatedRequest[A, Principal]

  trait OnBehalfOfPrincipal {
    def enrich: WSRequest => WSRequest
  }

  val originalServiceHeaderName = "X-Gu-Original-Service"

  def getIdentity(principal: Principal): String = principal.accessor.identity

  def validateUser(authedUser: AuthenticatedUser, userValidationEmailDomain: String, multifactorChecker: Option[Google2FAGroupChecker]): Boolean = {
    val isValidDomain = authedUser.user.email.endsWith("@" + userValidationEmailDomain)
    val passesMultifactor = if(multifactorChecker.nonEmpty) { authedUser.multiFactor } else { true }

    isValidDomain && passesMultifactor
  }
}
