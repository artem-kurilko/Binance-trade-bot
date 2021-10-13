package com.tradebot.service;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * This interface is designed to provide common methods for crypto exchanges
 * to execute code in multi-threaded mode.
 *
 * @author Artemii Kurilko
 * @version 1.0
 */
public interface OrdersService {

    /**
     * Is used to place buy/sell order for crypto exchange.
     * @param isBuy boolean
     */
    void placeOrder(boolean isBuy) throws Exception;

    /**
     * Returns estimated account balance in chosen currency.
     * @param symbol string
     * @return float value.
     */
    float getTotalBalance(String symbol) throws Exception;

    /**
     * Returns all user's active orders.
     * @return json array.
     */
    JSONArray getActiveOrders() throws Exception;

    /**
     * Returns last user's active order.
     * @return json object.
     */
    JSONObject getLastActiveOrder() throws Exception;

    /**
     * Returns user's orders history.
     * @return json array.
     */
    JSONArray getOrdersHistory() throws Exception;

    /**
     * Returns last user's order history.
     * @return json object.
     */
    JSONObject getLastOrdersHistory() throws Exception;

    /**
     * Is used to cancel user's order by given id.
     * @param clientOrderId client order id
     */
    void cancelOrder(String clientOrderId) throws Exception;

    /**
     * Is used to get nft boxes.
     */
    String getNFTBoxes() throws Exception;

    /**
     * Returns average price for a symbol.
     * @return double.
     */
    double getAveragePrice() throws Exception;

    /**
     * Returns user's balance of chosen symbol.
     * @param symbol string
     * @return string.
     */
    String getCurrencyBalance(String symbol) throws Exception;

    /**
     * Returns user's BTC balance.
     * @return string.
     */
    String getBTCBalance() throws Exception;

    /**
     * Returns user's USDT balance.
     * @return string.
     */
    String getUSDTBalance() throws Exception;

}
