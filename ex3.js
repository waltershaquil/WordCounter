var pall;
function contachar(){
    pall = document.getElementById("texto").value;
    var espacos = pall.split(" ").length - 1;
    var letras1 = pall.length;
    var letras= letras1 - espacos;
    document.getElementById("chara").innerHTML= letras;
}
function contapal(){
    pall = document.getElementById("texto").value;
    document.getElementById("words").innerHTML = pall.split(/\s/).length;
}
