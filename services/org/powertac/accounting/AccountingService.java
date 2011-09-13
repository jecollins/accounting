/*
 * Copyright 2009-2011 the original author or authors.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.log4j.Logger;
import org.joda.time.Instant;
import org.powertac.common.*;
import org.powertac.common.enumerations.TariffTransactionType;
import org.powertac.common.exceptions.*;
import org.powertac.common.interfaces.Accounting;
import org.powertac.common.interfaces.BrokerProxy;
import org.powertac.common.interfaces.CompetitionControl;
import org.powertac.common.interfaces.TimeslotPhaseProcessor;
import org.powertac.common.interfaces.TransactionProcessor;
import org.powertac.common.msg.*;
import org.powertac.common.repo.BrokerRepo;
import org.powertac.common.repo.TariffRepo;
import org.powertac.common.repo.TimeslotRepo;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Implementation of {@link org.powertac.common.interfaces.Accounting}
 *
 * @author John Collins
 */
public class AccountingService
  implements TransactionProcessor, Accounting, TimeslotPhaseProcessor 
{
  static private Logger log = Logger.getLogger(AccountingService.class.getName());

  @Autowired
  private TimeService timeService;
  
  @Autowired
  private TariffRepo tariffRepo;
  
  @Autowired
  private TimeslotRepo timeslotRepo;
  
  @Autowired
  private BrokerRepo brokerRepo;

  @Autowired
  private CompetitionControl competitionControlService;

  @Autowired
  private BrokerProxy brokerProxyService;

  private ArrayList<BrokerTransaction> pendingTransactions;

  // read this from plugin config
  private double bankInterest = 0.0;

  private int simulationPhase = 3;

  public AccountingService ()
  {
    super();
    pendingTransactions = new ArrayList<BrokerTransaction>();
  }
  
  /**
   * Register for phase 3 activation, to drive tariff publication
   */
  public void init(PluginConfig config) 
  {
    pendingTransactions.clear();
    competitionControlService.registerTimeslotPhase(this, simulationPhase);
    String interestSpec = config.getConfiguration().get("bankInterest");
    if (interestSpec != null) {
      bankInterest = Double.parseDouble(interestSpec);
    }
    else {
      log.error("Bank interest not configured. Default to " + bankInterest);
    }
  }

  public synchronized MarketTransaction 
  addMarketTransaction(Broker broker,
                       Timeslot timeslot,
                       double price,
                       double quantity) 
  {
    MarketTransaction mtx = new MarketTransaction(broker, timeService.getCurrentTime(),
                                                  timeslot, price, quantity);
    pendingTransactions.add(mtx);
    return mtx;
  }

  public synchronized TariffTransaction 
  addTariffTransaction(TariffTransactionType txType,
                       Tariff tariff,
                       CustomerInfo customer,
                       int customerCount,
                       double quantity,
                       double charge) 
  {
    // Note that tariff may be stale
    TariffTransaction ttx = new TariffTransaction(tariff.getBroker(),
                                                  timeService.getCurrentTime(), txType, 
                                                  tariffRepo.findSpecificationById(tariff.getSpecId()),
                                                  customer, customerCount,
                                                  quantity, charge);
    pendingTransactions.add(ttx);
    return ttx;
  }

  public synchronized DistributionTransaction 
  addDistributionTransaction(Broker broker,
                             double quantity,
                             double charge) 
  {
    DistributionTransaction dtx = new DistributionTransaction(broker, 
                                                              timeService.getCurrentTime(), 
                                                              quantity, charge);
    pendingTransactions.add(dtx);
    return dtx;
  }

  public synchronized BalancingTransaction 
  addBalancingTransaction(Broker broker,
                          double quantity,
                          double charge) 
  {
    BalancingTransaction btx = new BalancingTransaction(broker,
                                                        timeService.getCurrentTime(),
                                                        quantity, charge);
    pendingTransactions.add(btx);
    return btx;
  }

  // Gets the net load. Note that this only works BEFORE the day's transactions
  // have been processed.
  public synchronized double getCurrentNetLoad (Broker broker) 
  {
    double netLoad = 0.0;
    for (BrokerTransaction btx : pendingTransactions) {
      if (btx instanceof TariffTransaction) {
        TariffTransaction ttx = (TariffTransaction)btx;
        if (ttx.getBroker().getUsername() == broker.getUsername()) {
          if (ttx.getTxType() == TariffTransactionType.CONSUME ||
              ttx.getTxType() == TariffTransactionType.PRODUCE) {
            netLoad += ttx.getQuantity();
          }
        }
      }
    }
    return netLoad;
  }

  /**
   * Gets the net market position for the current timeslot. This only works on
   * processed transactions, but it can be used before activation in case there
   * can be no new market transactions for the current timeslot. This is the
   * normal case.
   */

  public synchronized double getCurrentMarketPosition(Broker broker) 
  {
    Timeslot current = timeslotRepo.currentTimeslot();
    log.debug("current timeslot: " + current.getSerialNumber());
    MarketPosition position =
        broker.findMarketPositionByTimeslot(current);
    if (position == null) {
      log.debug("null position for ts " + current.getSerialNumber());
      return 0.0;
    }
    return position.getOverallBalance();
  }

  // keep in mind that this will likely be called in a different
  // session from the one in which the transaction was created, so
  // the transactions themselves may be stale.
  public synchronized void activate(Instant time, int phaseNumber) 
  {
    HashMap<Broker, List<BrokerTransaction>> brokerMsg = new HashMap<Broker, List<BrokerTransaction>>();
    for (Broker broker : brokerRepo.list()) {
      brokerMsg.put(broker, new ArrayList<BrokerTransaction>());
    }
    // walk through the pending transactions and run the updates
    for (BrokerTransaction tx : pendingTransactions) {
      // need to refresh the transaction first
      if (tx.getBroker() == null) {
        log.error("tx " + tx.getClass().getName() + ":" + tx.getId() + 
                  " has null broker");
      }
      brokerMsg.get(tx.getBroker()).add(tx);
      tx.process((TransactionProcessor)this, brokerMsg.get(tx.getBroker()));
      //processTransaction(tx, brokerMsg.get(tx.getBroker()));
    }
    pendingTransactions.clear();
    // for each broker, compute interest and send messages
    double rate = bankInterest / 365.0;
    for (Broker broker : brokerRepo.list()) {
      // run interest payments at midnight
      if (timeService.getHourOfDay() == 0) {
        double brokerRate = rate;
        CashPosition cash = broker.getCash();
        if (cash.getBalance() >= 0.0) {
          // rate on positive balance is 1/2 of negative
          brokerRate /= 2.0;
        }
        double interest = cash.getBalance() * brokerRate;
        brokerMsg.get(broker).add(new BankTransaction(broker, interest,
                                                      timeService.getCurrentTime()));
        cash.deposit(interest);
      }
      // add the cash position to the list and send messages
      //brokerMsg[broker.username] << broker.cash
      brokerProxyService.sendMessages(broker, brokerMsg.get(broker));
    }
  }

  // process a tariff transaction
  public void processTransaction(TariffTransaction tx, List messages) {
    updateCash(tx.getBroker(), tx.getCharge());
  }

  // process a balance transaction
  public void processTransaction(BalancingTransaction tx, List messages) {
    updateCash(tx.getBroker(), tx.getCharge());
  }

  // process a DU fee transaction
  public void processTransaction(DistributionTransaction tx, List messages) {
    updateCash(tx.getBroker(), tx.getCharge());
  }

  // process a market transaction
  public void processTransaction(MarketTransaction tx, List messages) 
  {
    Broker broker = tx.getBroker();
    updateCash(broker, -tx.getPrice() * Math.abs(tx.getQuantity()));
    MarketPosition mkt =
        broker.findMarketPositionByTimeslot(tx.getTimeslot());
    if (mkt == null) {
      mkt = new MarketPosition(broker, tx.getTimeslot(), tx.getQuantity());
      log.debug("New MarketPosition(" + broker.getUsername() + 
                ", " + tx.getTimeslot().getSerialNumber() + "): " + 
                mkt.getId());
      //broker.addToMarketPositions(mkt);
    }
    messages.add(mkt);
    //log.debug("MarketPosition count = " + MarketPosition.count());
  }

  private void updateCash(Broker broker, double amount) 
  {
    CashPosition cash = broker.getCash();
    cash.deposit(amount);
  }

  @Override
  public void processTransaction (BrokerTransaction tx, List messages)
  {
    log.error("call to generic processTransaction - should not happen");   
  }
}