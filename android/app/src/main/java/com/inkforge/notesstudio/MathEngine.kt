package com.inkforge.notesstudio

import kotlin.math.*

/** Bounded expression parser; no JavaScript, eval, or network execution. */
class MathEngine(private val variables:Map<String,Double> = emptyMap(),private val degrees:Boolean=false){
    data class Result(val value:Double,val variable:String?=null){
        fun display():String=java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
    private var text="";private var pos=0;private var depth=0
    fun calculate(expression:String):Result{
        require(expression.length<=4096){"수식이 너무 깁니다."}
        text=normalize(expression);pos=0;depth=0
        var variable:String?=null
        val assignment=Regex("^([A-Za-z][A-Za-z0-9_]*)=").find(text)
        if(assignment!=null){variable=assignment.groupValues[1];text=text.substring(assignment.value.length)}
        val value=sum();space();require(pos==text.length){"수식 확인: ${text.substring(pos)}"};require(value.isFinite()){ "계산 결과가 유한한 수가 아닙니다." }
        return Result(value,variable)
    }
    private fun space(){while(pos<text.length&&text[pos].isWhitespace())pos++}
    private fun take(c:Char):Boolean{space();if(pos<text.length&&text[pos]==c){pos++;return true};return false}
    private fun sum():Double{var x=product();while(true)x=when{take('+')->x+product();take('-')->x-product();else->return x}}
    private fun product():Double{var x=unary();while(true)x=when{take('*')->x*unary();take('/')->{val y=unary();require(y!=0.0){"0으로 나눌 수 없습니다."};x/y};else->return x}}
    private fun unary():Double{require(++depth<100){"수식 중첩이 너무 깊습니다."};try{return when{take('+')->unary();take('-')->-unary();else->power()}}finally{depth--}}
    private fun power():Double{var x=atom();while(take('%'))x/=100;if(take('^'))x=x.pow(unary());return x}
    private fun atom():Double{
        if(take('(')){val x=sum();require(take(')')){"닫는 괄호가 필요합니다."};return x}
        space();val start=pos
        if(pos<text.length&&(text[pos].isDigit()||text[pos]=='.')){
            while(pos<text.length&&(text[pos].isDigit()||text[pos]=='.'))pos++
            return text.substring(start,pos).toDoubleOrNull()?:error("숫자 형식 오류")
        }
        while(pos<text.length&&(text[pos].isLetterOrDigit()||text[pos]=='_'))pos++
        require(pos>start){"수식이 완성되지 않았습니다."}
        val name=text.substring(start,pos).lowercase()
        if(!take('('))return when(name){"pi"->PI;"e"->E;else->variables[name]?:error("알 수 없는 변수: $name")}
        val value=sum();require(take(')')){"닫는 괄호가 필요합니다."}
        val angle=if(degrees)value*PI/180 else value
        return when(name){"sin"->sin(angle);"cos"->cos(angle);"tan"->tan(angle);"sqrt"->sqrt(value);"log"->log10(value);"ln"->ln(value);"abs"->abs(value);"exp"->exp(value);else->error("지원하지 않는 함수: $name")}
    }
    private fun normalize(source:String):String{
        var s=source.trim().removeSuffix("=").replace("×","*").replace("·","*").replace("÷","/").replace("−","-").replace("π","pi").replace("\\cdot","*").replace("\\times","*").replace("\\left","").replace("\\right","")
        repeat(12){s=s.replace(Regex("\\\\frac\\{([^{}]*)}\\{([^{}]*)}")){"((${it.groupValues[1]})/(${it.groupValues[2]}))"}}
        s=s.replace(Regex("\\\\sqrt\\{([^{}]*)}")){"sqrt(${it.groupValues[1]})"}.replace("√(","sqrt(").replace('{','(').replace('}',')')
        return s.replace(Regex("\\\\(sin|cos|tan|log|ln)")){it.groupValues[1]}
    }
}
