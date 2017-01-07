package org.wasabifx.wasabi.protocol.http

import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpResponseStatus.CONTINUE
import io.netty.handler.codec.http.HttpResponseStatus.OK
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import org.wasabifx.wasabi.app.AppServer
import org.wasabifx.wasabi.interceptors.InterceptOn
import org.wasabifx.wasabi.interceptors.InterceptorEntry
import org.wasabifx.wasabi.routing.*
import java.net.InetSocketAddress


fun findRoute(routes: Set<Route>, requestedPath: String, requestMethod: HttpMethod): Route {
    val matchingPaths = routes.filter { it.compareSegmentsToPath(requestedPath) }
    if (matchingPaths.count() == 0) {
        throw RouteNotFoundException()
    }
    val matchingVerbs = (matchingPaths.filter { it.method == requestMethod })

    if (matchingVerbs.count() > 0) {
        val matchedRoute = if (matchingVerbs.count() == 1)
            matchingVerbs.first()
        else
            matchingVerbs.firstOrNull { it.path == requestedPath }

        return matchedRoute ?: matchingVerbs.findMostWeightyBy(requestedPath)!!
    }
    throw MethodNotAllowedException(allowedMethods = matchingVerbs.map { it.method }.toTypedArray())
}

// Http Request -> HttpRequestHandler -> JsonHandler (passes in body of previous handler) ->

// Http Request comes in
// HttpRequestHandler decodes Headers
// Passes body to correct Handler (JSon, Multipart )
// Header, Body
// RouteHandler (Header, Body)
// OutboundSerializerHandlers
// OutboundComposer (aggregates -> ....



class HttpRequestHandler(private val appServer: AppServer) : SimpleChannelInboundHandler<FullHttpRequest>() {
    val preRequestInterceptors = lazy { appServer.interceptors.filter { it.interceptOn == InterceptOn.PreRequest} }.value

    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        try {
            val request = Request(msg, ctx.channel().remoteAddress() as InetSocketAddress)
            val response = Response()
            val route = findRoute(appServer.routes, request.path, msg.method())
            request.setRouteParams(route)
            runPrerequestInterceptors(request, response, route)
            for (handler in route.handler) {
                val handlerExtension: RouteHandler.() -> Unit = handler
                val routeHandler = RouteHandler(request, response)
                routeHandler.handlerExtension()
                if (!routeHandler.executeNext) {
                    break
                }
            }
            if (HttpUtil.is100ContinueExpected(msg)) {
                ctx.write(DefaultFullHttpResponse(HTTP_1_1, CONTINUE))
            }
            val keepAlive = HttpUtil.isKeepAlive(msg)
            val content = "Hello World".toByteArray()
            val nettyresponse = DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(content))
            nettyresponse.headers().set("Content-Type", "text/plain")
            nettyresponse.headers().setInt("Content-Length", nettyresponse.content().readableBytes())

            if (!keepAlive) {
                ctx.write(nettyresponse).addListener(io.netty.channel.ChannelFutureListener.CLOSE)
            } else {
                nettyresponse.headers().set("Connection", "kee-alive")
                ctx.write(nettyresponse)
            }

        } catch (e: MethodNotAllowedException) {
            // TODO: Clean up - this is a test. Later remove this and let channelRead throw exception. Handle it in an ExceptionHandler down the pipeline
            val nettyresponse = DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(e.message?.toByteArray()))
            nettyresponse.headers().set("Content-Type", "text/plain")
            nettyresponse.headers().setInt("Content-Length", nettyresponse.content().readableBytes())
            nettyresponse.headers().set("Connection", "kee-alive")
            ctx.write(nettyresponse)
        }


    }

    private fun runPrerequestInterceptors(request: Request, response: Response, route: Route? = null) {
        val interceptors = preRequestInterceptors.filter { if (route == null) { it.path == "*"} else { it.path == route.path }}
        interceptors.forEach {
            val continueRun = it.interceptor.intercept(request, response)
            if (!continueRun) {
                return@forEach
            }
        }
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    /*
        lateinit var request: Request
        val factory = DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE)
        val preRequestInterceptors = appServer.interceptors.filter { it.interceptOn == InterceptOn.PreRequest }
        val preExecutionInterceptors = appServer.interceptors.filter { it.interceptOn == InterceptOn.PreExecution }
        val postExecutionInterceptors = appServer.interceptors.filter { it.interceptOn == InterceptOn.PostExecution }
        val postRequestInterceptors = appServer.interceptors.filter { it.interceptOn == InterceptOn.PostRequest }
        val errorInterceptors = appServer.interceptors.filter { it.interceptOn == InterceptOn.Error }
        var chunkedTransfer = false
        private var bypassPipeline = false
        val response = Response()
        var deserializer : Deserializer? = null
        var decoder : HttpPostRequestDecoder? = null
       // private var log = LoggerFactory.getLogger(HttpRequestHandler::class.java)
        // TODO make configurable.
        val routeLocator = PatternAndVerbMatchingRouteLocator(appServer.routes)
        var exceptionLocator = ClassMatchingExceptionHandlerLocator(appServer.exceptionHandlers)

        override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?)  {
            if (msg is HttpRequest) {
                request = Request(msg, ctx!!.channel().remoteAddress() as InetSocketAddress)

                request.accept.mapTo(response.requestedContentTypes, { it.key })

                if (request.method == HttpMethod.POST || request.method == HttpMethod.PUT || request.method == HttpMethod.PATCH) {
                    deserializer = appServer.deserializers.firstOrNull { it.canDeserialize(request.contentType) }
                    // TODO: Re-do this as it's now considering special case for multi-part
                    if (request.contentType.contains("application/x-www-form-urlencoded") || request.contentType.contains("multipart/form-data")) {
                        decoder = HttpPostRequestDecoder(factory, msg)
                        chunkedTransfer = request.chunked
                    }
                }
            }

            if (msg is HttpContent) {

                runInterceptors(preRequestInterceptors)

                if (deserializer != null) {
                    // TODO: Add support for chunked transfer
                    deserializeBody(msg)
                }

                if (msg is LastHttpContent) {
                    try {

                        // process all interceptors that are global
                        runInterceptors(preExecutionInterceptors)

                        // Only need to check here to stop RouteNotFoundException
                        if (!bypassPipeline) {

                            val routeHandlers = routeLocator.findRoute(request.uri.split('?')[0], request.method)
                            request.routeParams.putAll(setRouteParams(routeHandlers.path, request.uri.split('?')[0]))

                            // process the route specific pre execution interceptors
                            runInterceptors(preExecutionInterceptors, routeHandlers)

                            // Now that the global and route specific preexecution interceptors have run, execute the route handlers
                            runHandlers(routeHandlers)

                            // process the route specific post execution interceptors
                            runInterceptors(postExecutionInterceptors, routeHandlers)
                        }

                    } catch (e: InvalidMethodException)  {
                        response.setAllowedMethods(e.allowedMethods)
                        response.setStatus(StatusCodes.MethodNotAllowed)
                    } catch (e: RouteNotFoundException) {
                        response.setStatus(StatusCodes.NotFound)
                    } catch (e: Exception) {
               //         log!!.debug("Exception during web invocation: ${e.message}")
                        // bypassPipeline = true
                        val handler = exceptionLocator.findExceptionHandlers(e).handler
                        val extension: ExceptionHandler.() -> Unit = handler
                        val exceptionHandler = ExceptionHandler(request, response, e)
                        exceptionHandler.extension()
                    } finally {
                        if (!bypassPipeline) {
                            // Run global interceptors again
                            runInterceptors(postExecutionInterceptors)
                        }
                        writeResponse(ctx!!, response)
                    }
                }
            }
        }

        private fun runHandlers(routeHandlers : Route)
        {
            // If the flag has been set no-op to allow the response to be flushed as is.
            if (bypassPipeline)
            {
                return
            }
            for (handler in routeHandlers.handler) {

                val handlerExtension : RouteHandler.() -> Unit = handler
                val routeHandler = RouteHandler(request, response)

                routeHandler.handlerExtension()
                if (!routeHandler.executeNext) {
                    break
                }
            }
        }

        private fun runInterceptors(interceptors: List<InterceptorEntry>, route: Route? = null) {
            // If the flag has been set no-op to allow the response to be flushed as is.
          */
/*  if (bypassPipeline)
        {
            return
        }
        val interceptorsToRun : List<InterceptorEntry>
        if (route == null) {
            interceptorsToRun = interceptors.filter { it.path == "*" }
        } else {
            interceptorsToRun = interceptors.filter { routeLocator.compareRouteSegments(route, it.path) }
        }
        for ((interceptor) in interceptorsToRun) {

            val executeNext = interceptor.intercept(request, response)

            if (!executeNext) {
                bypassPipeline = true
                break
            }
        }
   *//*
 }

    private fun writeResponse(ctx: ChannelHandlerContext, response: Response) {
        val httpResponse : HttpResponse
        if (response.statusCode / 100 == 4 || response.statusCode / 100 == 5) {
            runInterceptors(errorInterceptors)
        }

        // @TODO move this charset stuff
        var responseCharset = ""
        val responseContentAsStream : ChunkedInput<ByteBuf> = if (response.negotiatedMediaType == "application/octet-stream" || response.sendBuffer is ByteArray) {
            val responseBytes = response.sendBuffer as ByteArray
            response.contentLength = responseBytes.size.toLong()
            ChunkedStream(responseBytes.inputStream())
        } else {
            var buffer = ""
            if (response.sendBuffer == null) {
                // Allows us to check downstream
                buffer = ""
            } else if (response.sendBuffer is String) {
                if (response.sendBuffer as String != "") {
                    buffer = (response.sendBuffer as String)
                }
            } else if (response.negotiatedMediaType != "") {
                val serializer = appServer.serializers.firstOrNull { it.canSerialize(response.negotiatedMediaType) }
                if (serializer != null) {
                    // TODO waiting on ISSUE-62 atm we are forcing UTF-8.
                    // TODO Given we only have XML/JSON atm its not terrible but still sucks rocks.
                    responseCharset = ";charset=UTF-8"
                    buffer = serializer.serialize(response.sendBuffer!!)
                } else {
                    response.setStatus(StatusCodes.UnsupportedMediaType)
                }
            }

            // TODO refactor above buffer logic.
            // This allows postRequestInterceptors to override 405 and us to
            // appropriately set the response to the description.
            if(buffer == "") {
                buffer = response.statusDescription
            }
            ChunkedStream(buffer.byteInputStream(Charsets.UTF_8))
        }

        runInterceptors(postRequestInterceptors)

        httpResponse = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus(response.statusCode, response.statusDescription))
        response.contentType = response.negotiatedMediaType + responseCharset
        response.connection = "keep-alive"
        response.contentLength = 12L
        addResponseHeaders(httpResponse, response)
        ctx.write(httpResponse)
        ctx.write(responseContentAsStream)

*/
/*
        val lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
        lastContentFuture.addListener(ChannelFutureListener.CLOSE)
*//*

    }

    private fun addResponseHeaders(httpResponse: HttpResponse, response: Response) {
        for ((key, value) in response.getHeaders()) {
            if (value != "") {
                httpResponse.headers().add(key, value)
            }
        }
    }

    private fun deserializeBody(msg: HttpContent) {
        if (decoder != null) {
            decoder!!.offer(msg)
            request.bodyParams.putAll(deserializer!!.deserialize(decoder!!.bodyHttpDatas))
        } else {
            // TODO: Add support for CharSet
            val buffer = msg.content()
            if (buffer.isReadable) {
                val data = buffer.toString(CharsetUtil.UTF_8)
                request.bodyParams.putAll(deserializer!!.deserialize(data!!))
            }
        }
    }

    // TODO: Clean this up.
    private fun setRouteParams(route: String, path: String): HashMap<String, String> {
        val segments1 = route.split('/')
        val segments2 = path.split('/')
        if (segments1.size != segments2.size) {
            return hashMapOf()
        }
        val params = hashMapOf<String, String>()
        var i = 0
        for (segment in segments1) {
            if (segment.startsWith(':')) {
                params.put(segment.drop(1), segments2[i])
            }
            i++
        }
        return params

    }
*/

}
