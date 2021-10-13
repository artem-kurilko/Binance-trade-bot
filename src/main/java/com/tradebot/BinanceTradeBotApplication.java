package com.tradebot;

import com.tradebot.service.BinanceOrdersServiceImpl;

/**
 * Binance bot application.
 *
 * @author Artemii Kurilko
 * @version 1.0
 */
public class BinanceTradeBotApplication {
    private static final BinanceOrdersServiceImpl binanceService = new BinanceOrdersServiceImpl();

    public static void main(String[] args) throws Exception {
        System.out.println("Program has been started.");
    }
}
