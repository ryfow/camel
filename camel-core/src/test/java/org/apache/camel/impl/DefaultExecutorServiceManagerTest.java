/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.camel.ContextTestSupport;
import org.apache.camel.ThreadPoolRejectedPolicy;
import org.apache.camel.spi.ThreadPoolProfile;
import org.apache.camel.util.concurrent.ThreadHelper;

/**
 * @version 
 */
public class DefaultExecutorServiceManagerTest extends ContextTestSupport {
    
    public void testGetThreadName() {
        String name = ThreadHelper.resolveThreadName("Camel Thread ${counter} - ${name}", "foo");

        assertTrue(name.startsWith("Camel Thread"));
        assertTrue(name.endsWith("foo"));
    }

    public void testGetThreadNameDefaultPattern() throws Exception {
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("Camel (" + context.getName() + ") thread "));
        assertTrue(foo.endsWith("foo"));
        assertTrue(bar.startsWith("Camel (" + context.getName() + ") thread "));
        assertTrue(bar.endsWith("bar"));
    }

    public void testGetThreadNameCustomPattern() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("#${counter} - ${name}");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#"));
        assertTrue(foo.endsWith(" - foo"));
        assertTrue(bar.startsWith("#"));
        assertTrue(bar.endsWith(" - bar"));
    }

    public void testGetThreadNameCustomPatternCamelId() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("#${camelId} - #${counter} - ${name}");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#" + context.getName() + " - #"));
        assertTrue(foo.endsWith(" - foo"));
        assertTrue(bar.startsWith("#" + context.getName() + " - #"));
        assertTrue(bar.endsWith(" - bar"));
    }

    public void testGetThreadNameCustomPatternWithDollar() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("Hello - ${name}");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo$bar");

        assertEquals("Hello - foo$bar", foo);
    }

    public void testGetThreadNameCustomPatternLongName() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("#${counter} - ${longName}");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo?beer=Carlsberg");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#"));
        assertTrue(foo.endsWith(" - foo?beer=Carlsberg"));
        assertTrue(bar.startsWith("#"));
        assertTrue(bar.endsWith(" - bar"));
    }

    public void testGetThreadNameCustomPatternWithParameters() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("#${counter} - ${name}");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo?beer=Carlsberg");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertTrue(foo.startsWith("#"));
        assertTrue(foo.endsWith(" - foo"));
        assertTrue(bar.startsWith("#"));
        assertTrue(bar.endsWith(" - bar"));
    }

    public void testGetThreadNameCustomPatternNoCounter() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("Cool ${name}");
        String foo = context.getExecutorServiceManager().resolveThreadName("foo");
        String bar = context.getExecutorServiceManager().resolveThreadName("bar");

        assertNotSame(foo, bar);
        assertEquals("Cool foo", foo);
        assertEquals("Cool bar", bar);
    }

    public void testGetThreadNameCustomPatternInvalid() throws Exception {
        context.getExecutorServiceManager().setThreadNamePattern("Cool ${xxx}");
        try {
            context.getExecutorServiceManager().resolveThreadName("foo");
            fail("Should thrown an exception");
        } catch (IllegalArgumentException e) {
            assertEquals("Pattern is invalid: Cool ${xxx}", e.getMessage());
        }

        // reset it so we can shutdown properly
        context.getExecutorServiceManager().setThreadNamePattern("Camel Thread ${counter} - ${name}");
    }

    public void testDefaultThreadPool() throws Exception {
        ExecutorService myPool = context.getExecutorServiceManager().getDefaultExecutorService("myPool", this);
        assertEquals(false, myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(10, executor.getCorePoolSize());
        assertEquals(20, executor.getMaximumPoolSize());
        assertEquals(60, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(1000, executor.getQueue().remainingCapacity());

        context.stop();
        assertEquals(true, myPool.isShutdown());
    }

    public void testDefaultUnboundedQueueThreadPool() throws Exception {
        ThreadPoolProfile custom = new ThreadPoolProfile("custom");
        custom.setPoolSize(10);
        custom.setMaxPoolSize(30);
        custom.setKeepAliveTime(50L);
        custom.setMaxQueueSize(-1);

        context.getExecutorServiceManager().setDefaultThreadPoolProfile(custom);
        assertEquals(true, custom.isDefaultProfile().booleanValue());

        ExecutorService myPool = context.getExecutorServiceManager().getDefaultExecutorService("myPool", this);
        assertEquals(false, myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(10, executor.getCorePoolSize());
        assertEquals(30, executor.getMaximumPoolSize());
        assertEquals(50, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(Integer.MAX_VALUE, executor.getQueue().remainingCapacity());

        context.stop();
        assertEquals(true, myPool.isShutdown());
    }

    public void testCustomDefaultThreadPool() throws Exception {
        ThreadPoolProfile custom = new ThreadPoolProfile("custom");
        custom.setKeepAliveTime(20L);
        custom.setMaxPoolSize(40);
        custom.setPoolSize(5);
        custom.setMaxQueueSize(2000);

        context.getExecutorServiceManager().setDefaultThreadPoolProfile(custom);
        assertEquals(true, custom.isDefaultProfile().booleanValue());

        ExecutorService myPool = context.getExecutorServiceManager().getDefaultExecutorService("myPool", this);
        assertEquals(false, myPool.isShutdown());

        // should use default settings
        ThreadPoolExecutor executor = (ThreadPoolExecutor) myPool;
        assertEquals(5, executor.getCorePoolSize());
        assertEquals(40, executor.getMaximumPoolSize());
        assertEquals(20, executor.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(2000, executor.getQueue().remainingCapacity());

        context.stop();
        assertEquals(true, myPool.isShutdown());
    }

    public void testGetThreadPoolProfile() throws Exception {
        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));
    }

    public void testTwoGetThreadPoolProfile() throws Exception {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        ThreadPoolProfile bar = new ThreadPoolProfile("bar");
        bar.setKeepAliveTime(40L);
        bar.setMaxPoolSize(5);
        bar.setPoolSize(1);
        bar.setMaxQueueSize(100);

        context.getExecutorServiceManager().registerThreadPoolProfile(bar);

        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        assertSame(bar, context.getExecutorServiceManager().getThreadPoolProfile("bar"));
        assertNotSame(foo, bar);

        assertFalse(context.getExecutorServiceManager().getThreadPoolProfile("foo").isDefaultProfile());
        assertFalse(context.getExecutorServiceManager().getThreadPoolProfile("bar").isDefaultProfile());
    }

    public void testGetThreadPoolProfileInheritDefaultValues() throws Exception {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("fooProfile"));
        ThreadPoolProfile foo = new ThreadPoolProfile("fooProfile");
        foo.setMaxPoolSize(40);
        context.getExecutorServiceManager().registerThreadPoolProfile(foo);
        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("fooProfile"));

        ExecutorService executor = context.getExecutorServiceManager().getDefaultExecutorService("fooProfile", this);
        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, executor);
        assertEquals(40, tp.getMaximumPoolSize());
        // should inherit the default values
        assertEquals(10, tp.getCorePoolSize());
        assertEquals(60, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertIsInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, tp.getRejectedExecutionHandler());
    }

    public void testGetThreadPoolProfileInheritCustomDefaultValues() throws Exception {
        ThreadPoolProfile newDefault = new ThreadPoolProfile("newDefault");
        newDefault.setKeepAliveTime(30L);
        newDefault.setMaxPoolSize(50);
        newDefault.setPoolSize(5);
        newDefault.setMaxQueueSize(2000);
        newDefault.setRejectedPolicy(ThreadPoolRejectedPolicy.Abort);
        context.getExecutorServiceManager().setDefaultThreadPoolProfile(newDefault);

        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setMaxPoolSize(25);
        foo.setPoolSize(1);
        context.getExecutorServiceManager().registerThreadPoolProfile(foo);
        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ExecutorService executor = context.getExecutorServiceManager().getDefaultExecutorService("foo", this);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, executor);
        assertEquals(25, tp.getMaximumPoolSize());
        // should inherit the default values
        assertEquals(1, tp.getCorePoolSize());
        assertEquals(30, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertIsInstanceOf(ThreadPoolExecutor.AbortPolicy.class, tp.getRejectedExecutionHandler());
    }

    public void testGetThreadPoolProfileInheritCustomDefaultValues2() throws Exception {
        ThreadPoolProfile newDefault = new ThreadPoolProfile("newDefault");
        // just change the max pool as the default profile should then inherit the old default profile
        newDefault.setMaxPoolSize(50);
        context.getExecutorServiceManager().setDefaultThreadPoolProfile(newDefault);

        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));
        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setPoolSize(1);
        context.getExecutorServiceManager().registerThreadPoolProfile(foo);
        assertSame(foo, context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ExecutorService executor = context.getExecutorServiceManager().getDefaultExecutorService("foo", this);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, executor);
        assertEquals(1, tp.getCorePoolSize());
        // should inherit the default values
        assertEquals(50, tp.getMaximumPoolSize());
        assertEquals(60, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertIsInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, tp.getRejectedExecutionHandler());
    }

    public void testNewThreadPoolProfile() throws Exception {
        assertNull(context.getExecutorServiceManager().getThreadPoolProfile("foo"));

        ThreadPoolProfile foo = new ThreadPoolProfile("foo");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        ExecutorService pool = context.getExecutorServiceManager().getDefaultExecutorService("foo", this);
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(20, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(40, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

    public void testLookupThreadPoolProfile() throws Exception {
        ThreadPoolProfile foo = new ThreadPoolProfile("fooProfile");
        foo.setKeepAliveTime(20L);
        foo.setMaxPoolSize(40);
        foo.setPoolSize(5);
        foo.setMaxQueueSize(2000);

        context.getExecutorServiceManager().registerThreadPoolProfile(foo);

        Object pool = context.getExecutorServiceManager().getDefaultExecutorService("fooProfile", this);
        assertNotNull(pool);

        ThreadPoolExecutor tp = assertIsInstanceOf(ThreadPoolExecutor.class, pool);
        assertEquals(20, tp.getKeepAliveTime(TimeUnit.SECONDS));
        assertEquals(40, tp.getMaximumPoolSize());
        assertEquals(5, tp.getCorePoolSize());
        assertFalse(tp.isShutdown());

        context.stop();

        assertTrue(tp.isShutdown());
    }

}