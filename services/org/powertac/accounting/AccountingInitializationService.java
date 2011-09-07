/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.powertac.accounting;

import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;
import org.powertac.common.Competition;
import org.powertac.common.PluginConfig;
import org.powertac.common.interfaces.InitializationService;
import org.powertac.common.interfaces.RandomSeedService;
import org.powertac.common.repo.PluginConfigRepo;
import org.powertac.common.repo.TariffRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Pre-game initialization for the accounting service
 * @author John Collins
 */
@Service
public class AccountingInitializationService 
    implements InitializationService
{
  static private Logger log = Logger.getLogger(AccountingInitializationService.class.getName());

  @Autowired
  AccountingService accountingService;
  
  @Autowired
  PluginConfigRepo pluginConfigRepo;
  
  @Autowired
  RandomSeedService randomSeedService;
  Random randomGen;
  
  double minInterest = 0.04;
  double maxInterest = 0.12;
  
  @Override
  public void setDefaults ()
  {
    long randomSeed = randomSeedService.nextSeed("AccountingInitializationService",
                                                 0l, "interest");
    randomGen = new Random(randomSeed);

    double interest = (minInterest + 
		       (randomGen.nextDouble() *
			(maxInterest - minInterest)));

    log.info("bank interest: " + interest);
    PluginConfig accounting =
      pluginConfigRepo.makePluginConfig("AccountingService", "init")
        .addConfiguration("bankInterest", Double.toString(interest));
  }

  @Override
  public String initialize (Competition competition, List<String> completedInits)
  {
    PluginConfig accountingConfig = pluginConfigRepo.findByRoleName("AccountingService");
    if (accountingConfig == null) {
      log.error("PluginConfig for AccountingService does not exist");
    }
    else {
      accountingService.init(accountingConfig);
      return "AccountingService";
    }
    return "fail";
  }
}
