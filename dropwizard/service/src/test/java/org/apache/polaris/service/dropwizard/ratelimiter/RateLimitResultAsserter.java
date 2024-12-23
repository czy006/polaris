/*
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
 */
package org.apache.polaris.service.dropwizard.ratelimiter;

import org.apache.polaris.service.ratelimiter.RateLimiter;
import org.junit.jupiter.api.Assertions;

/** Utility class for testing rate limiters. Lets you easily assert the result of tryAcquire(). */
public class RateLimitResultAsserter {
  private final RateLimiter rateLimiter;

  public RateLimitResultAsserter(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  public void canAcquire(int times) {
    for (int i = 0; i < times; i++) {
      Assertions.assertTrue(rateLimiter.tryAcquire());
    }
  }

  public void cantAcquire() {
    for (int i = 0; i < 5; i++) {
      Assertions.assertFalse(rateLimiter.tryAcquire());
    }
  }
}
