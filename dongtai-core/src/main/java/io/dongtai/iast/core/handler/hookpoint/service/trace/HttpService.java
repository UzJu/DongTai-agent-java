package io.dongtai.iast.core.handler.hookpoint.service.trace;

import io.dongtai.iast.core.handler.context.ContextManager;
import io.dongtai.iast.core.handler.hookpoint.models.MethodEvent;
import io.dongtai.iast.core.handler.hookpoint.models.policy.PolicyNode;
import io.dongtai.iast.core.handler.hookpoint.models.policy.SignatureMethodMatcher;
import io.dongtai.iast.core.handler.hookpoint.service.HttpClient;
import io.dongtai.iast.core.utils.ReflectUtils;
import io.dongtai.log.DongTaiLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;

public class HttpService implements ServiceTrace {
    private String matchedSignature;

    @Override
    public boolean match(MethodEvent event, PolicyNode policyNode) {
        if (policyNode.getMethodMatcher() instanceof SignatureMethodMatcher) {
            this.matchedSignature = ((SignatureMethodMatcher) policyNode.getMethodMatcher()).getSignature().toString();
        }

        return HttpClient.match(this.matchedSignature);
    }

    @Override
    public void addTrace(MethodEvent event, PolicyNode policyNode) {
        String traceId = null;
        if (HttpClient.matchJavaNetUrl(this.matchedSignature)) {
            traceId = addTraceToJavaNetURL(event);
        } else if (HttpClient.matchApacheHttp4(this.matchedSignature)
                || HttpClient.matchApacheHttp5(this.matchedSignature)) {
            traceId = addTraceToApacheHttpClient(event);
        } else if (HttpClient.matchApacheHttp3(this.matchedSignature)) {
            traceId = addTraceToApacheHttpClientLegacy(event);
        } else if (HttpClient.matchOkhttp(this.matchedSignature)) {
            traceId = addTraceToOkhttp(event);
        }

        if (traceId != null && !traceId.isEmpty()) {
            event.traceId = traceId;
        }
    }

    private String addTraceToJavaNetURL(MethodEvent event) {
        if (event.objectInstance == null) {
            return null;
        }
        try {
            if (event.objectInstance instanceof HttpURLConnection) {
                final HttpURLConnection connection = (HttpURLConnection) event.objectInstance;
                final String traceId = ContextManager.nextTraceId();
                connection.setRequestProperty(ContextManager.getHeaderKey(), traceId);
                return traceId;
            }
        } catch (IllegalStateException ignore) {
        } catch (Throwable e) {
            DongTaiLog.warn("add traceId header to java.net.URLConnection failed", e);
        }
        return null;
    }

    private String addTraceToApacheHttpClient(MethodEvent event) {
        Object obj;
        if (HttpClient.matchApacheHttp5(this.matchedSignature)) {
            obj = event.parameterInstances[1];
        } else {
            obj = event.objectInstance;
        }
        if (obj == null) {
            return null;
        }
        try {
            Method method;
            if (HttpClient.matchApacheHttp5(this.matchedSignature)) {
                method = ReflectUtils.getDeclaredMethodFromSuperClass(obj.getClass(),
                        "addHeader", new Class[]{String.class, Object.class});
            } else {
                method = ReflectUtils.getDeclaredMethodFromSuperClass(obj.getClass(),
                        "addHeader", new Class[]{String.class, String.class});
            }
            if (method == null) {
                return null;
            }
            final String traceId = ContextManager.nextTraceId();
            method.invoke(obj, ContextManager.getHeaderKey(), traceId);
            return traceId;
        } catch (Throwable e) {
            DongTaiLog.warn("add traceId header to apache http client failed", e);
        }
        return null;
    }

    private String addTraceToApacheHttpClientLegacy(MethodEvent event) {
        Object obj = event.objectInstance;
        if (obj == null) {
            return null;
        }
        try {
            Method method = ReflectUtils.getDeclaredMethodFromSuperClass(obj.getClass(),
                    "setRequestHeader", new Class[]{String.class, String.class});
            if (method == null) {
                return null;
            }
            final String traceId = ContextManager.nextTraceId();
            method.invoke(obj, ContextManager.getHeaderKey(), traceId);
            return traceId;
        } catch (Throwable e) {
            DongTaiLog.warn("add traceId header to apache legacy http client failed", e);
        }
        return null;
    }

    private String addTraceToOkhttp(MethodEvent event) {
        Object obj = event.objectInstance;
        if (obj == null) {
            return null;
        }
        try {
            String className = obj.getClass().getName();
            if (!HttpClient.matchAllOkhttpCallClass(className)) {
                return null;
            }

            Field reqField = obj.getClass().getDeclaredField("originalRequest");
            boolean accessible = reqField.isAccessible();
            reqField.setAccessible(true);
            Object req = reqField.get(obj);

            Method methodNewBuilder = req.getClass().getMethod("newBuilder");
            Object reqBuilder = methodNewBuilder.invoke(req);
            Method methodAddHeader = reqBuilder.getClass().getMethod("addHeader", String.class, String.class);
            final String traceId = ContextManager.nextTraceId();
            methodAddHeader.invoke(reqBuilder, ContextManager.getHeaderKey(), traceId);
            Method methodBuild = reqBuilder.getClass().getMethod("build");
            Object newReq = methodBuild.invoke(reqBuilder);
            reqField.set(obj, newReq);
            reqField.setAccessible(accessible);
            return traceId;
        } catch (Throwable e) {
            DongTaiLog.warn("add traceId header to okhttp client failed", e);
        }
        return null;
    }

    public static boolean validateURLConnection(MethodEvent event) {
        if (!HttpClient.matchJavaNetUrl(event.signature)) {
            return true;
        }

        Object obj = event.objectInstance;
        if (obj == null) {
            return false;
        }

        try {
            // check if the traceId header has been set (by spring cloud etc...)
            Field userHeadersField = ReflectUtils.getDeclaredFieldFromSuperClassByName(obj.getClass(), "userHeaders");
            if (userHeadersField == null) {
                return false;
            }
            userHeadersField.setAccessible(true);
            Object userHeaders = userHeadersField.get(obj);
            Method getKeyMethod = userHeaders.getClass().getMethod("getKey", String.class);
            int hasKey = (int) getKeyMethod.invoke(userHeaders, ContextManager.getHeaderKey());
            // already has traceId header
            if (hasKey != -1) {
                return false;
            }

            Field inputStreamField = ReflectUtils.getDeclaredFieldFromSuperClassByName(obj.getClass(), "inputStream");
            if (inputStreamField == null) {
                return false;
            }
            inputStreamField.setAccessible(true);
            Object inputStream = inputStreamField.get(obj);

            // inputStream has cache, only first invoke getInputStream() need to collect
            if (inputStream == null) {
                return true;
            }
        } catch (Throwable e) {
            DongTaiLog.warn("validate URLConnection failed", e);
        }
        return false;
    }
}
