package com.oldwu.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.misec.apiquery.ApiList;
import com.misec.login.Verify;
import com.misec.pojo.userinfobean.Data;
import com.misec.task.TaskInfoHolder;
import com.misec.utils.HelpUtil;
import com.misec.utils.HttpUtil;
import com.oldwu.dao.AutoBilibiliDao;
import com.oldwu.dao.BiliUserDao;
import com.oldwu.entity.AutoBilibili;
import com.oldwu.entity.BiliPlan;
import com.oldwu.entity.BiliUser;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.misec.task.TaskInfoHolder.STATUS_CODE_STR;
import static com.misec.task.TaskInfoHolder.userInfo;

@Service
public class BiliService {

    @Autowired
    private AutoBilibiliDao autoBilibiliDao;

    @Autowired
    private BiliUserDao biliUserDao;

    public List<BiliPlan> getAllPlan(){
        List<BiliPlan> newPlans = new ArrayList<>();
        for (BiliPlan biliPlan : biliUserDao.selectAll()) {
            biliPlan.setBiliName(HelpUtil.userNameEncode(biliPlan.getBiliName()));
            newPlans.add(biliPlan);
        }
        return newPlans;
    }

    public List<BiliPlan> getMyPlan(Integer userid){
        return biliUserDao.selectMine(userid);
    }

    public Map<String, String> addBiliPlan(AutoBilibili autoBilibili) {
        Map<String, String> map = new HashMap<>();
        Map<String, Object> stringObjectMap = checkForm(autoBilibili);
        if (!(boolean) stringObjectMap.get("flag")) {
            map.put("code", "-1");
            map.put("msg", (String) stringObjectMap.get("msg"));
            return map;
        }
        //信息检查完毕后，使用cookie尝试登录账号，进行验证
        Map<String, String> usercheck = checkUser(autoBilibili);
        if (usercheck.get("flag").equals("false")) {
            map.put("code", "-1");
            map.put("msg", usercheck.get("msg"));
            return map;
        }
        //账号验证成功
        map.put("code", "200");
        map.put("msg", usercheck.get("msg"));
        return map;
    }

    /**
     * 校验用户，成功写入任务数据，失败返回msg
     * @param autoBilibili
     * @return
     */
    public Map<String, String> checkUser(AutoBilibili autoBilibili) {
        Map<String, String> map = new HashMap<>();
        String requestPram = "";
        Verify.verifyInit(autoBilibili.getDedeuserid(), autoBilibili.getSessdata(), autoBilibili.getBiliJct());
        JsonObject userJson = HttpUtil.doGet(ApiList.LOGIN + requestPram);
        if (userJson == null) {
            map.put("flag", "false");
            map.put("msg", "用户信息请求失败，如果是412错误，请在config.json中更换UA，412问题仅影响用户信息确认，不影响任务");
            return map;
        } else {
            userJson = HttpUtil.doGet(ApiList.LOGIN);
            //判断Cookies是否有效
            if (userJson.get(STATUS_CODE_STR).getAsInt() == 0
                    && userJson.get("data").getAsJsonObject().get("isLogin").getAsBoolean()) {
                userInfo = new Gson().fromJson(userJson
                        .getAsJsonObject("data"), Data.class);
//                log.info("Cookies有效，登录成功");
            } else {
//                log.debug(String.valueOf(userJson));
                map.put("flag", "false");
                map.put("s", "cookie");
                map.put("msg", "Cookies可能失效了,请仔细检查配置中的DEDEUSERID SESSDATA BILI_JCT三项的值是否正确、过期");
                return map;
            }
        }
        String s = userInfo.getUname();
        long mid = userInfo.getMid();
        //判断用户表中是否存在该用户
        BiliUser biliUser = biliUserDao.selectByMid(mid);
        if (biliUser == null || biliUser.getId() == null) {
            //将数据储存到任务表以及获取用户信息储存到biliuser表和bili任务表
            autoBilibiliDao.insertSelective(autoBilibili);
            if (autoBilibili.getId() <= 0) {
                map.put("flag", "false");
                map.put("msg", "数据库错误！添加任务信息失败！");
                return map;
            }
            boolean b = updateUserInfo(autoBilibili, userInfo, false);
            if (!b) {
                map.put("flag", "false");
                map.put("msg", "数据库错误！添加用户信息失败！");
                return map;
            }
            map.put("msg", "检查登录信息成功：" + s);
            map.put("flag", "true");
            return map;
        } else {
            //更新用户信息
            Integer autoId = biliUser.getAutoId();
            autoBilibili.setId(autoId);
            int i = autoBilibiliDao.updateByPrimaryKeySelective(autoBilibili);
            if (i <= 0) {
                map.put("flag", "false");
                map.put("msg", "数据库错误！更新任务信息失败！");
                return map;
            }
            updateUserInfo(autoBilibili,userInfo,true);
            map.put("msg", "更新原有登录信息成功：" + s);
            map.put("flag", "true");
            return map;
        }
    }

    public boolean updateUserInfo(AutoBilibili autoBilibili,Data userInfo,boolean update){
        BiliUser biliUser1 = new BiliUser();
        biliUser1.setAutoId(autoBilibili.getId());
        biliUser1.setBiliCoin(userInfo.getMoney());
        biliUser1.setUid(userInfo.getMid());
        biliUser1.setBiliName(userInfo.getUname());
        biliUser1.setBiliLevel(userInfo.getLevel_info().getCurrent_level());
        biliUser1.setBiliExp((long) userInfo.getLevel_info().getCurrent_exp());
        biliUser1.setBiliUpexp((long) userInfo.getLevel_info().getNext_exp_asInt());
        biliUser1.setFaceImg(userInfo.getFace());
        biliUser1.setIsVip(TaskInfoHolder.queryVipStatusType() == 0 ? "false" : "true");
        biliUser1.setVipDueDate(new Date(userInfo.getVipDueDate()));
        if (!update){
            //增加用户信息
            return biliUserDao.insertSelective(biliUser1) > 0;
        }else {
            //update
            biliUser1.setAutoId(autoBilibili.getId());
            return biliUserDao.updateByAutoIdSelective(biliUser1) > 0;
        }
    }

    /**
     * 校验表单，失败返回《flag=false，msg=msg》
     *
     * @param autoBilibili
     * @return
     */
    public Map<String, Object> checkForm(AutoBilibili autoBilibili) {
        Map<String, Object> map = new HashMap<>();
        String biliJct = autoBilibili.getBiliJct();
        String dedeuserid = autoBilibili.getDedeuserid();
        String sessdata = autoBilibili.getSessdata();
        if (StringUtils.isBlank(biliJct) || StringUtils.isBlank(dedeuserid) || StringUtils.isBlank(sessdata)) {
            map.put("flag", false);
            map.put("msg", "cookie的三项都不能为空！");
            return map;
        }
        if (StringUtils.isBlank(autoBilibili.getName())) {
            map.put("flag", false);
            map.put("msg", "任务名不能为空！");
            return map;
        }
        Integer taskintervaltime = autoBilibili.getTaskintervaltime();
        if (taskintervaltime == null || taskintervaltime < 1) {
            autoBilibili.setTaskintervaltime(10);
        }
        Integer numberofcoins = autoBilibili.getNumberofcoins();
        if (numberofcoins == null || numberofcoins > 5 || numberofcoins < 1) {
            autoBilibili.setNumberofcoins(5);
        }
        Integer reservecoins = autoBilibili.getReservecoins();
        if (reservecoins == null || reservecoins < 0) {
            autoBilibili.setReservecoins(50);
        }
        Integer selectlike = autoBilibili.getSelectlike();
        if (selectlike == null || selectlike > 1 || selectlike < 0) {
            autoBilibili.setSelectlike(0);
        }
        String monthendautocharge = autoBilibili.getMonthendautocharge();
        if (StringUtils.isBlank(monthendautocharge) || !monthendautocharge.equals("true") && !monthendautocharge.equals("false")) {
            autoBilibili.setMonthendautocharge("true");
        }
        String uplive = autoBilibili.getUplive();
        if (StringUtils.isBlank(uplive) || !uplive.equals("true") && !uplive.equals("false")) {
            autoBilibili.setUplive("true");
        }
        String chargeforlove = autoBilibili.getChargeforlove();
        if (StringUtils.isBlank(chargeforlove) || !StringUtils.isNumeric(chargeforlove)) {
            autoBilibili.setChargeforlove("0");
        }
        String deviceplatform = autoBilibili.getDeviceplatform();
        if (StringUtils.isNumeric(deviceplatform) || !deviceplatform.equals("ios") && !deviceplatform.equals("android")) {
            autoBilibili.setDeviceplatform("ios");
        }
        Integer coinaddpriority = autoBilibili.getCoinaddpriority();
        if (coinaddpriority == null || coinaddpriority < 0 || coinaddpriority > 1) {
            autoBilibili.setCoinaddpriority(1);
        }
        String useragent = autoBilibili.getUseragent();
        if (StringUtils.isBlank(useragent)) {
            autoBilibili.setUseragent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36 Edg/86.0.622.69");
        }
        String skipdailytask = autoBilibili.getSkipdailytask();
        if (StringUtils.isBlank(skipdailytask) || !skipdailytask.equals("true") && !skipdailytask.equals("false")) {
            autoBilibili.setSkipdailytask("false");
        }
        String serverpushkey = autoBilibili.getServerpushkey();
        if (StringUtils.isBlank(serverpushkey)) {
            autoBilibili.setServerpushkey(null);
        }
        map.put("flag", true);
        map.put("msg", "check complete");
//        map.put("data", autoBilibili);
        return map;
    }

}