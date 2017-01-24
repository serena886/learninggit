package com.vip.xfd.trade.trade.impl;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

second change
first change
add sth
oh my lady
stupid booss
import com.vip.xfd.trade.adapter.LicaiPayAdapter;
import com.vip.xfd.trade.common.manager.BaseManager;
import com.vip.xfd.trade.common.processor.AbstractProcessorService;
import com.vip.xfd.trade.common.result.Result;
import com.vip.xfd.trade.exception.XfdTradeException;
import com.vip.xfd.trade.exception.XfdTradeExceptionEnum;
import com.vip.xfd.trade.major.model.FinanceAccountModel;
import com.vip.xfd.trade.major.service.OrmFinanceAccountService;
import com.vip.xfd.trade.service.TradeEventModel;
import com.vip.xfd.trade.trade.BusAssemblePayService;
import com.vip.xfd.trade.trade.BusBorrowService;
import com.vip.xfd.trade.trade.BusPeriodPayService;
import com.vip.xfd.trade.trade.BusTradeLogService;
import com.vip.xfd.trade.trade.bo.VfmAccountBO;
import com.vip.xfd.trade.trade.constant.ConfigCenterConstants;
import com.vip.xfd.trade.trade.constant.SwitchKeyConstants;
import com.vip.xfd.trade.trade.constant.TradeEventConstants;
import com.vip.xfd.trade.trade.constant.TradeSequenceConstants;
import com.vip.xfd.trade.util.MathUtil;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusAssemblePayServiceImpl extends AbstractProcessorService implements BusAssemblePayService {
    private final Logger logger = LoggerFactory.getLogger(BusAssemblePayServiceImpl.class);

    @Autowired
    private BaseManager baseManager;
    @Autowired
    private OrmFinanceAccountService ormFinanceAccountService;
    @Autowired
    private BusBorrowService busBorrowService;
    @Autowired
    private BusTradeLogService busTradeLogService;
    @Autowired
    private LicaiPayAdapter licaiPayAdapter;
    @Autowired
    private BusPeriodPayService busPeriodPayService;

    @Override
    @Transactional("transactionManager")
    public void assemblePay(TradeEventModel tradeEvent) throws Exception {
        Result<Boolean> result = new Result<>(true);
        result.setDefaultResult(true);
        result.setResult("transactionId", tradeEvent.getTransactionId());

        String uid = tradeEvent.getUid();
        String msg = "";
        String orderNo = tradeEvent.getRelatedTradeId();
        String tradeLogId = baseManager.getNextShortId(TradeSequenceConstants.XFD_TRANSACTION, uid);
        FinanceAccountModel financeAccount = ormFinanceAccountService.getFinanceAccountByUid(uid);
        BigDecimal changedAmount = new BigDecimal(tradeEvent.getChangedAmount());
        BigDecimal vcpAmount = new BigDecimal(tradeEvent.getVcpAmount());// 唯品花
        BigDecimal vfmAmount = new BigDecimal(tradeEvent.getVfmAmount()); // 唯品宝

        // 入参检查
        baseManager.checkTradeEvent(tradeEvent);

        // 风控检查
        if (baseManager.getSwitchByConfigAndKey(ConfigCenterConstants.XFD_TRADE_SWITCH_CONFIG, SwitchKeyConstants.FDS_CHECK_PAY, true)) {
                /*Result<Boolean> checkResult = baseManager.securityCheck(result,orderNo);
				if(!checkResult.getDefaultResult()){
					return checkResult;
				}*/
        }

        // 检查支付参数
        baseManager.checkPayParam(tradeEvent, changedAmount, financeAccount);

        // 唯品宝可用余额检查
        checkVfmCanUseAmount(tradeEvent);

        Integer periodNum = tradeEvent.getPeriodNum();
        Boolean isPeriodPay = periodNum != null && periodNum > Integer.valueOf(0);
        // 如果是分期 则 borrowType=21 否则为 20
        String operateType = isPeriodPay ? TradeEventConstants.ASSEMBLE_XFD_PERIOD : TradeEventConstants.ASSEMBLE_XFD;

        // 插入借款表
        busBorrowService.insertBorrow(tradeEvent, operateType, vcpAmount);

        // 更新账户额度
        recoverQuota(uid, tradeEvent.getTransactionId(), vcpAmount);

        // 如果是组合分期支付，插入分期
        if (periodNum != null && periodNum > Integer.valueOf(0)) {
            busPeriodPayService.doPeriodPay(tradeEvent, tradeLogId);
        }

        // 调用理财接口，唯品宝支付
        if (vfmAmount != null && MathUtil.biggerThan(vfmAmount, BigDecimal.ZERO)) {
            Result<Boolean> licaiPayRs = licaiPayAdapter.licaiPay(tradeEvent);
            if (licaiPayRs == null || !licaiPayRs.isSuccess() || !licaiPayRs.getDefaultResult()) {
            	logger.error("调用唯品宝支付失败,relatedTradeId为"+tradeEvent.getRelatedTradeId());
                throw new XfdTradeException(XfdTradeExceptionEnum.VFM_AMOUNT_PAY_ERROR);
            }
        }

        // 插入tradelog
        busTradeLogService.insertTradeLog(tradeEvent, msg, true);
        if (MathUtil.biggerThan(vcpAmount, BigDecimal.ZERO)) {
            busTradeLogService.insertAssembleTradeLog(tradeEvent, operateType, tradeEvent.getVcpAmount(), tradeEvent.getTransactionId(), msg, true);
        }
        if (MathUtil.biggerThan(vfmAmount, BigDecimal.ZERO)) {
            busTradeLogService .insertAssembleTradeLog(tradeEvent, TradeEventConstants.ASSEMBLE_VFM, tradeEvent.getVfmAmount(), tradeEvent.getTransactionId(), msg, true);
        }


    }

    @Override
    public BigDecimal checkVfmCanUseAmount(TradeEventModel tradeEvent) throws Exception {
        BigDecimal vfmCanUseAmount = new BigDecimal(BigDecimal.ZERO.toString());
        BigDecimal vfmAmount = new BigDecimal(tradeEvent.getVfmAmount());
        Result<VfmAccountBO> vfmResult = licaiPayAdapter.queryLicaiAccount(tradeEvent.getVUserId(), tradeEvent.getChannel());

        VfmAccountBO vfmAccountBO = null;
        if (vfmResult != null && vfmResult.isSuccess()) {
            vfmAccountBO = vfmResult.getDefaultResult();
        }

        if (vfmAccountBO == null || StringUtils.isBlank(vfmAccountBO.getCanUseAmount())) {
            throw new XfdTradeException(XfdTradeExceptionEnum.VFM_USER_NOT_EXIST);
        }
        vfmCanUseAmount = new BigDecimal(vfmAccountBO.getCanUseAmount());

        if (MathUtil.biggerThan(vfmAmount, vfmCanUseAmount)) {
            throw new XfdTradeException(XfdTradeExceptionEnum.VFM_AMOUNT_CAN_NOT_FIX_ASSEMBLE_PAY);
        }

        return vfmCanUseAmount;
    }

}
