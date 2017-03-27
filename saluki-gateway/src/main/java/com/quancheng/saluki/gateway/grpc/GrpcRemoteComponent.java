/*
 * Copyright (c) 2017, Quancheng-ec.com All right reserved. This software is the
 * confidential and proprietary information of Quancheng-ec.com ("Confidential
 * Information"). You shall not disclose such Confidential Information and shall
 * use it only in accordance with the terms of the license agreement you entered
 * into with Quancheng-ec.com.
 */
package com.quancheng.saluki.gateway.grpc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.quancheng.saluki.boot.SalukiReference;
import com.quancheng.saluki.core.common.NamedThreadFactory;
import com.quancheng.saluki.core.grpc.service.GenericService;
import com.quancheng.saluki.core.utils.ReflectUtils;

import sun.misc.BASE64Encoder;

@SuppressWarnings("restriction")
@Component
public class GrpcRemoteComponent {

    private static final Logger                logger              = LoggerFactory.getLogger(GrpcRemoteComponent.class);

    private static final Map<String, Class<?>> remoteServiceCache  = Maps.newConcurrentMap();

    private static final Gson                  GSON                = new Gson();

    private static final String                AUTHOR              = new BASE64Encoder().encode(("liushiming:Hello899").getBytes());

    @SalukiReference
    private GenericService                     genricService;

    @Autowired
    private ApiJarRepository                   apiJarRepository;

    private String                             API_DIR_PATH        = System.getProperty("user.home") + "/saluki";

    private String                             API_JAR_PATH;

    private URLClassLoader                     urlClassLoader;

    private final ScheduledExecutorService     downLoadApiExecutor = Executors.newScheduledThreadPool(1,
                                                                                                      new NamedThreadFactory("downLoadApi",
                                                                                                                             true));

    @PostConstruct
    public void init() {

        API_JAR_PATH = API_DIR_PATH + File.separator + "api.jar";
        downLoadApiExecutor.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    List<ApiJar> jars = apiJarRepository.findLastesJar();
                    String jarUrl = jars.get(0).getJarUrl();
                    downloadApiJar(jarUrl);
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }, 0, 1, TimeUnit.DAYS);
        try {
            URL jarUrl = new File(API_JAR_PATH).toURI().toURL();
            urlClassLoader = new URLClassLoader(new URL[] { jarUrl });
        } catch (MalformedURLException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private void downloadApiJar(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3 * 1000);
        conn.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 5.0; Windows NT; DigExt)");
        conn.setRequestProperty("Authorization", "Basic " + AUTHOR);
        InputStream inputStream = conn.getInputStream();
        byte[] getData = readInputStream(inputStream);
        File saveDir = new File(API_DIR_PATH);
        if (!saveDir.exists()) {
            saveDir.mkdir();
        }
        File file = new File(API_JAR_PATH);
        file.deleteOnExit();
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(getData);
        if (fos != null) {
            fos.close();
        }
        if (inputStream != null) {
            inputStream.close();
        }
        logger.info(url + " download success");
    }

    private byte[] readInputStream(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        int len = 0;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while ((len = inputStream.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        bos.close();
        return bos.toByteArray();
    }

    public Object callRemoteService(String serviceName, String group, String version, String methodName,
                                    String requestParam) throws Throwable {
        try {
            Class<?> serviceClass = remoteServiceCache.get(serviceName);
            if (serviceClass == null) {
                serviceClass = ReflectUtils.name2class(urlClassLoader, serviceName);
                remoteServiceCache.put(serviceName, serviceClass);
            }
            Method method = ReflectUtils.findMethodByMethodName(serviceClass, methodName);
            Class<?> requestType = method.getParameterTypes()[0];
            Class<?> returnType = method.getReturnType();
            Object request = GSON.fromJson(requestParam, requestType);
            String[] paramTypes = new String[] { requestType.getName(), returnType.getName() };
            Object[] args = new Object[] { request };
            Object reply = genricService.$invoke(serviceName, group, version, methodName, paramTypes, args);
            return reply;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            throw e;
        }
    }

}
