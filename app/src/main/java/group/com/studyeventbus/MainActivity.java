package group.com.studyeventbus;

import android.app.Activity;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Created by faker on 2017/7/27.
 */

public class MainActivity extends Activity {
    @Subscribe(threadMode = ThreadMode.POSTING, priority = 10)
    public void handlerLogin(Activity activity){
        //做些什么。。。
    }
}
