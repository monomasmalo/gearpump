/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gearpump.services

import akka.actor.{ActorSystem}
import akka.http.scaladsl.model.{Uri, StatusCodes, RemoteAddress}
import akka.http.scaladsl.model.headers.{HttpCookiePair, HttpCookie, HttpChallenge}
import akka.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsRejected, CredentialsMissing}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.FormFieldDirectives.FieldMagnet
import akka.stream.Materializer
import com.softwaremill.session.ClientSessionManagerMagnet._
import com.softwaremill.session.SessionDirectives._
import com.softwaremill.session._
import com.typesafe.config.Config
import io.gearpump.services.SecurityService.{User, UserSession}
import io.gearpump.services.security.oauth2.OAuth2Authenticator
import io.gearpump.util.{Constants, LogUtil}
import upickle.default.{write}
import io.gearpump.services.util.UpickleUtil._
import io.gearpump.security.{Authenticator => BaseAuthenticator}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


/**
 * When user cannot be authenticated, will reject with 401 AuthenticationFailedRejection
 * When user can be authenticated, but are not authorized to access certail resource, will
 * return a 405 AuthorizationFailedRejection.
 *
 * When web UI frontend receive 401, it should redirect the UI to login page.
 * When web UI receive 405,it should display errors like
 * "current user is not authorized to access this resource."
 *
 * The Authenticator used is pluggable, the current Authenticator is resolved by looking up config path
 * [[Constants.GEARPUMP_UI_AUTHENTICATOR_CLASS]].
 *
 * see [[BaseAuthenticator]] to find more info on custom Authenticator.
 *
 */
class SecurityService(inner: RouteService, implicit val system: ActorSystem) extends RouteService {

  // Use scheme "GearpumpBasic" to avoid popping up web browser native authentication box.
  private val challenge = HttpChallenge(scheme = "GearpumpBasic", realm = "gearpump", params = Map.empty)

  val LOG = LogUtil.getLogger(getClass, "AUDIT")

  private val config = system.settings.config
  private val sessionConfig = SessionConfig.fromConfig(config)
  private implicit val sessionManager = new SessionManager[UserSession](sessionConfig)
  private val magnet = ClientSessionManagerMagnet.forSessionManager[UserSession, Unit](Unit)

  private val authenticator = {
    val clazz = Class.forName(config.getString(Constants.GEARPUMP_UI_AUTHENTICATOR_CLASS))
    val constructor = clazz.getConstructor(classOf[Config])
    val authenticator = constructor.newInstance(config).asInstanceOf[BaseAuthenticator]
    authenticator
  }

  private def configToMap(config : Config, path: String) = {
    import scala.collection.JavaConverters._
    config.getConfig(path).root.unwrapped.asScala.toMap map { case (k, v) => k -> v.toString }
  }

  private val oauth2Providers: Map[String, String] = {
    if (config.getBoolean(Constants.GEARPUMP_UI_OAUTH2_AUTHENTICATOR_ENABLED)) {
      val map = configToMap(config, Constants.GEARPUMP_UI_OAUTH2_AUTHENTICATORS)
      map.keys.toList.map { key =>
        val iconPath = config.getString(s"${Constants.GEARPUMP_UI_OAUTH2_AUTHENTICATORS}.$key.icon")
        (key, iconPath)
      }.toMap
    } else {
      Map.empty[String, String]
    }
  }

  private def authenticate(user: String, pass: String)(implicit ec: ExecutionContext): Future[Option[UserSession]] = {
    authenticator.authenticate(user, pass, ec).map{ result =>
      if (result.authenticated) {
        Some(UserSession(user, result.permissionLevel))
      } else {
        None
      }
    }
  }

  private def rejectMissingCredentials: Route = {
    reject(AuthenticationFailedRejection(CredentialsMissing, challenge))
  }

  private def rejectWrongCredentials: Route = {
    reject(AuthenticationFailedRejection(CredentialsRejected, challenge))
  }

  private def requireAuthentication(inner: UserSession => Route): Route = {
    optionalSession(magnet) { sessionOption =>
      sessionOption match {
        case Some(session) => {
          inner(session)
        }
        case None =>
          rejectMissingCredentials
      }
    }
  }

  private def login(session: UserSession, ip: String, redirectToRoot: Boolean = false): Route = {
    setSession(session) {
      val user = session.user
      val maxAgeMs = 1000 * sessionConfig.clientSessionMaxAgeSeconds.getOrElse(24 * 3600L) // default 1 day
      setCookie(HttpCookie.fromPair(HttpCookiePair("username", user), path = Some("/"), maxAge = Some(maxAgeMs))) {
        LOG.info(s"user $user login from $ip")
        if (redirectToRoot) {
          redirect(Uri("/"), StatusCodes.TemporaryRedirect)
        } else {
          complete(write(new User(user)))
        }
      }
    }
  }

  private def logout(user: UserSession, ip: String): Route = {
    invalidateSession(magnet) { ctx =>
      LOG.info(s"user ${user.user} logout from $ip")
      ctx.complete(write(new User(user.user)))
    }
  }

  // Only admin are able to access operation like post/delete/put
  private def requireAuthorization(user: UserSession, route: => Route): Route = {
    // valid user
    if (user.permissionLevel >= BaseAuthenticator.User.permissionLevel) {
      route
    } else {
      // possibly a guest or not authenticated.
      (put | delete | post) {
        // reject with 405 authorization error
        reject(AuthorizationFailedRejection)
      } ~
      get {
        route
      }
    }
  }

  private val unknownIp: Directive1[RemoteAddress] = {
    Directive[Tuple1[RemoteAddress]]{ inner =>
      inner(new Tuple1(RemoteAddress.Unknown))
    }
  }

  override val route: Route = {

    extractExecutionContext{implicit ec: ExecutionContext =>
    extractMaterializer{implicit mat: Materializer =>
    (extractClientIP | unknownIp) { ip =>
     pathPrefix("login") {
       pathEndOrSingleSlash {
          get {
            getFromResource("login/login.html")
          } ~
          post {
            // Guest account don't have permission to submit new application in UI
            formField(FieldMagnet('username.as[String])) {user: String =>
              formFields(FieldMagnet('password.as[String])) {pass: String =>
                val result = authenticate(user, pass)
                onSuccess(result){
                  case Some(session) =>
                    login(session, ip.toString)
                  case None =>
                    rejectWrongCredentials
                }
              }
            }
          }
        } ~
        path ("oauth2" / "providers") {
          // respond with a list of OAuth2 providers.
          complete(write(oauth2Providers))
        } ~
        // Support OAUTH Authentication
        pathPrefix ("oauth2"/ Segment) {providerName =>
        // Resolve OAUTH Authentication Provider
        val oauthService = OAuth2Authenticator.get(config, providerName, ec)

          if (oauthService == null) {
            // OAuth2 is disabled.
            complete(StatusCodes.NotFound)
          } else {

            def loginWithOAuth2Parameters(parameters: Map[String, String]): Route = {
              val result = oauthService.authenticate(parameters)
              onComplete(result){
                case Success(session) =>
                  login(session, ip.toString, redirectToRoot = true)
                case Failure(ex) => {
                  LOG.info(s"Failed to login user from ${ip.toString}", ex)
                  rejectWrongCredentials
                }
              }
            }

            path ("authorize") {
              // redirect to OAuth2 service provider for authorization.
              redirect(Uri(oauthService.getAuthorizationUrl), StatusCodes.TemporaryRedirect)
            } ~
            path ("accesstoken") {
              post {
                // Guest account don't have permission to submit new application in UI
                formField(FieldMagnet('accesstoken.as[String])) {accesstoken: String =>
                  loginWithOAuth2Parameters(Map("accesstoken" -> accesstoken))
                }
              }
            } ~
            path("callback") {
              // Login with authorization code or access token.
              parameterMap {parameters =>
                loginWithOAuth2Parameters(parameters)
              }
            }
          }
        }
      } ~
      path("logout") {
        post {
          requireAuthentication {session =>
            logout(session, ip.toString())
          }
        }
      } ~
      requireAuthentication {user =>
        requireAuthorization(user, inner.route)
      }
    }}}

  }
}

object SecurityService {

  val SESSION_MANAGER_KEY = "akka.http.session.serverSecret"

  case class UserSession(user: String, permissionLevel: Int)

  object UserSession {
    implicit def serializer: SessionSerializer[UserSession] = new ToMapSessionSerializer[UserSession] {
      private val User = "user"
      private val PermissionLevel = "permissionLevel"

      override def serializeToMap(t: UserSession) = Map(User -> t.user, PermissionLevel->t.permissionLevel.toString)
      override def deserializeFromMap(m: Map[String, String]) = UserSession(m(User), m(PermissionLevel).toInt)
    }
  }

  case class User(user: String)
}