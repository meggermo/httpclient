/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.client.execchain;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.ProtocolException;
import org.apache.http.annotation.Immutable;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.util.Args;

/**
 * @since 4.3
 */
@Immutable
@SuppressWarnings("deprecation")
public class ProtocolExec implements ClientExecChain {

    private final Log log = LogFactory.getLog(getClass());

    private final ClientExecChain requestExecutor;
    private final HttpProcessor httpProcessor;

    public ProtocolExec(
            final ClientExecChain requestExecutor,
            final HttpProcessor httpProcessor) {
        Args.notNull(requestExecutor, "HTTP client request executor");
        Args.notNull(httpProcessor, "HTTP protocol processor");
        this.requestExecutor = requestExecutor;
        this.httpProcessor = httpProcessor;
    }

    private void rewriteRequestURI(
            final HttpRequestWrapper request,
            final HttpRoute route) throws ProtocolException {
        try {
            URI uri = request.getURI();
            if (uri != null) {
                if (route.getProxyHost() != null && !route.isTunnelled()) {
                    // Make sure the request URI is absolute
                    if (!uri.isAbsolute()) {
                        HttpHost target = route.getTargetHost();
                        uri = URIUtils.rewriteURI(uri, target, true);
                    } else {
                        uri = URIUtils.rewriteURI(uri);
                    }
                } else {
                    // Make sure the request URI is relative
                    if (uri.isAbsolute()) {
                        uri = URIUtils.rewriteURI(uri, null, true);
                    } else {
                        uri = URIUtils.rewriteURI(uri);
                    }
                }
                request.setURI(uri);
            }
        } catch (URISyntaxException ex) {
            throw new ProtocolException("Invalid URI: " +
                    request.getRequestLine().getUri(), ex);
        }
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException {
        Args.notNull(route, "HTTP route");
        Args.notNull(request, "HTTP request");
        Args.notNull(context, "HTTP context");

        // Get user info from the URI
        AuthState targetAuthState = context.getTargetAuthState();
        if (targetAuthState != null) {
            URI uri = request.getURI();
            if (uri != null) {
                String userinfo = uri.getUserInfo();
                if (userinfo != null) {
                    targetAuthState.update(
                            new BasicScheme(), new UsernamePasswordCredentials(userinfo));
                }
            }
        }

        // Re-write request URI if needed
        rewriteRequestURI(request, route);

        HttpParams params = request.getParams();
        HttpHost virtualHost = (HttpHost) params.getParameter(ClientPNames.VIRTUAL_HOST);
        // HTTPCLIENT-1092 - add the port if necessary
        if (virtualHost != null && virtualHost.getPort() == -1) {
            int port = route.getTargetHost().getPort();
            if (port != -1){
                virtualHost = new HttpHost(virtualHost.getHostName(), port, virtualHost.getSchemeName());
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("Using virtual host" + virtualHost);
            }
        }

        HttpHost target = null;
        if (virtualHost != null) {
            target = virtualHost;
        } else {
            HttpRequest original = request.getOriginal();
            if (original instanceof HttpUriRequest) {
                URI uri = ((HttpUriRequest) original).getURI();
                if (uri.isAbsolute()) {
                    target = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
                }
            }
        }
        if (target == null) {
            target = route.getTargetHost();
        }

        // Run request protocol interceptors
        context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, target);
        context.setAttribute(ClientContext.ROUTE, route);
        context.setAttribute(ExecutionContext.HTTP_REQUEST, request);

        this.httpProcessor.process(request, context);

        CloseableHttpResponse response = this.requestExecutor.execute(route, request, context, execAware);
        try {
            // Run response protocol interceptors
            context.setAttribute(ExecutionContext.HTTP_RESPONSE, response);
            this.httpProcessor.process(response, context);
            return response;
        } catch (RuntimeException ex) {
            response.close();
            throw ex;
        } catch (IOException ex) {
            response.close();
            throw ex;
        } catch (HttpException ex) {
            response.close();
            throw ex;
        }
    }

}
