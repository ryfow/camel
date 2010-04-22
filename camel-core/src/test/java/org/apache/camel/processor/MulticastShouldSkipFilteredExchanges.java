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
package org.apache.camel.processor;

import org.apache.camel.ContextTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.processor.aggregate.AggregationStrategy;

/**
 * Unit test to verify that Multicast aggregator does not included filtered exchanges.
 *
 * @version $Revision$
 */
public class MulticastShouldSkipFilteredExchanges extends ContextTestSupport {

    public void testMulticastWithFilterNotFiltered() throws Exception {
        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived("Hello World,Hello World");

        template.sendBody("direct:start", "Hello World");

        assertMockEndpointsSatisfied();
    }

    public void testMulticastWithFilterFiltered() throws Exception {
        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived("Hi there");

        template.sendBody("direct:start", "Hi there");

        assertMockEndpointsSatisfied();
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start")
                    .to("direct:multicast")
                    .to("mock:result");

                from("direct:multicast")
                    .multicast(new MyAggregationStrategy())
                    .to("direct:a")
                    .to("direct:b");

                Predicate goodWord = body().contains("World");
                from("direct:a", "direct:b")
                    .filter(goodWord)
                    .to("mock:filtered");
            }
        };
    }

    private class MyAggregationStrategy implements AggregationStrategy {

        public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
            String newBody = newExchange.getIn().getBody(String.class);
            assertTrue("Should have been filtered: " + newBody, newBody.contains("World"));

            if (oldExchange == null) {
                return newExchange;
            }

            String body = oldExchange.getIn().getBody(String.class);
            body = body + "," + newBody;
            oldExchange.getIn().setBody(body);
            return oldExchange;
        }

    }
}