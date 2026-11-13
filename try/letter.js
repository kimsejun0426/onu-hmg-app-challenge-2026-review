"use strict";
const $ = id => document.getElementById(id);
const audio = $("sound");
const scenes = {
  water: {chapter:"바다에서 온 편지", title:"바위 끝에서 난 소리", choices:[["wet","물소리"],["steps","발소리"]]},
  guess: {chapter:"바다에서 온 편지", title:"두 친구의 이름", choices:[["otter","해달"],["penguin","펭귄"],["both","둘 다 가능해"]]},
  question: {chapter:"바다에서 온 편지", title:"편지 속 친구에게", choices:[["feathers","깃털이 있어?"],["swim","수영 잘해?"],["wings","날개로 헤엄쳐?"]]},
  identify: {chapter:"두 번째 단서", title:"친구에게서 온 답", choices:[["penguin","펭귄"],["otter","해달"]]},
  direct: {chapter:"첫 점프 중계", title:"이번엔 네가 소리 감독", choices:[["soft","살금살금, 크게 첨벙!"],["bold","성큼성큼, 작게 퐁당!"]]},
  end: {chapter:"첫 점프 중계", title:"우리의 첫 점프 방송", choices:[["reply","펭귄의 답장 듣기"],["remix","다른 점프로 중계하기"]]},
  rain: {chapter:"두 번째 소리 편지", title:"첫 소리는 네가 골라 줘", choices:[["drops","톡톡 먼저"],["wiper","슥삭 먼저"]]},
  music: {chapter:"펭귄의 방송 음악", title:"우리가 만든 방송 음악", choices:[["swap","다른 순서로 듣기"],["restart","첫 편지부터 듣기"]]}
};
let stage = "";
let queue = [];
let index = 0;
let assisted = false;
let ending = "";
let musicOrder = "";
let generation = 0;
let state = "ready";
const replayCues = {water:["splash","water_q"],guess:["candidates","guess_q"],question:["ask_question"],identify:["feather_evidence","who_q"],direct:["reveal","direct_q"],rain:["rain_direct_q"]};
const warmed = new Set();
const poses = {water:"listening",guess:"thinking",question:"storytelling",identify:"thinking",direct:"storytelling",end:"delighted",rain:"thinking",music:"delighted"};
const art = {shore:"노을빛 바닷가와 낮은 바위.",rain:"차창에 빗방울이 내려앉은 비 오는 풍경."};

function drawPlayback() {
  document.body.classList.toggle("playing",state === "playing");
  $("notice").classList.toggle("complete",state === "answered" && stage === "music");
  $("replay").hidden = state !== "answered";
  $("pause").hidden = state === "answered" || state === "ready";
  $("pause").textContent = state === "error" ? "소리 다시 재생" : state === "paused" ? "이어서 듣기" : "잠깐 멈추기";
  $("skip").hidden = state === "answered" || state === "ready";
}
const choiceArt = {wet:"splash",soft:"splash",steps:"steps",otter:"otter",penguin:"penguin",feathers:"feather",wings:"wing",swim:"swim",bold:"plop",drops:"drops",wiper:"wiper"};
function choicePicture(name) {
  const image = document.createElement("img");
  image.className = "choice-icon";
  image.alt = "";
  image.src = "art/" + name + ".svg";
  return image;
}
function showChoices(asText) {
  generation++;
  audio.onended = null;
  audio.onerror = null;
  audio.pause();
  state = "answered";
  if (asText) {
    $("caption").textContent = queue.map(key => clips[key].text).join(" ");
    $("speaker").textContent = "편지 내용";
  }
  $("notice").textContent = stage === "music" ? (musicOrder === "drops" ? "이번엔 빗방울이 먼저였어." : "이번엔 와이퍼가 먼저였어.") : "";
  $("choices").replaceChildren();
  for (const [value, label] of scenes[stage].choices) {
    const choice = document.createElement("button");
    choice.type = "button";
    if (value === "both") {
      const pair = document.createElement("span");
      pair.className = "choice-pair";
      pair.append(choicePicture("otter"),choicePicture("penguin"));
      choice.append(pair);
    } else if (choiceArt[value]) {
      choice.append(choicePicture(choiceArt[value]));
    }
    choice.append(document.createTextNode(label));
    choice.dataset.answer = value;
    choice.addEventListener("click",() => answer(value));
    $("choices").append(choice);
  }
  drawPlayback();
  if (asText || document.activeElement === $("title") || document.activeElement === $("pause")) {
    $("choices").firstElementChild?.focus({preventScroll:true});
  }
}
function requestPlay(token) {
  state = "playing";
  $("notice").textContent = "";
  drawPlayback();
  audio.play().catch(() => {
    if (token !== generation || state !== "playing") return;
    state = "error";
    $("notice").textContent = "이 소리를 재생할 수 없어요. 다시 재생하거나 이번 부분을 글로 볼 수 있어요.";
    drawPlayback();
  });
}
function playClip() {
  const token = ++generation;
  const key = queue[index];
  const cue = clips[key];
  $("speaker").textContent = key === "splash" ? "편지 속 소리" : "오누";
  $("caption").textContent = cue.text;
  audio.onended = () => {
    if (token !== generation) return;
    index++;
    if (index === queue.length) showChoices(false);
    else playClip();
  };
  audio.onerror = () => {
    if (token !== generation) return;
    state = "error";
    $("notice").textContent = "소리를 불러오지 못했어요. 이번 부분을 글로 볼 수 있어요.";
    drawPlayback();
  };
  audio.src = cue.file;
  requestPlay(token);
  warmNext(queue[index + 1]);
}
function warmNext(key) {
  if (!key || warmed.has(key) || location.protocol === "file:") return;
  warmed.add(key);
  fetch(clips[key].file,{cache:"force-cache",credentials:"omit"})
    .then(response => { if (!response.ok) throw new Error("audio unavailable"); return response.arrayBuffer(); })
    .catch(() => warmed.delete(key));
}
function startQueue(cues) {
  queue = cues.slice();
  index = 0;
  $("choices").replaceChildren();
  playClip();
}
function openScene(next, cues, retry = false) {
  if (!retry) assisted = false;
  stage = next;
  const artName = next === "rain" || next === "music" ? "rain" : "shore";
  $("scene").src = "art/" + artName + ".webp";
  $("scene").alt = art[artName];
  $("onu").src = "art/" + poses[next] + ".webp";
  $("chapter").textContent = scenes[next].chapter;
  $("title").textContent = scenes[next].title;
  $("title").focus({preventScroll:true});
  startQueue(cues);
}
function answer(value) {
  if (state !== "answered") return;
  switch(stage) {
    case "water":
      return openScene("guess",[value === "wet" ? "water_ack" : "steps_ack","candidates","guess_q"]);
    case "guess":
      return openScene("question",["guess_" + value,"ask_question"]);
    case "question":
      if (value === "swim" && !assisted) {
        assisted = true;
        return openScene("question",["weak_shared"],true);
      }
      return openScene("identify",value === "swim" ? ["weak_shared","feather_evidence","who_q"] : ["feather_evidence","who_q"]);
    case "identify":
      if (value === "otter" && !assisted) {
        assisted = true;
        return openScene("identify",["hint_body","feather_evidence","who_q"],true);
      }
      return openScene("direct",["reveal","direct_q"]);
    case "direct":
      ending = value;
      return openScene("end",["finale_" + ending]);
    case "end":
      if (value === "reply") return openScene("rain",["rain_opening","memory_" + ending,"rain_direct_q"]);
      return openScene("direct",["reveal","direct_q"]);
    case "rain":
      return playMusic(value);
    case "music":
      if (value === "swap") return playMusic(musicOrder === "drops" ? "wiper" : "drops");
      return begin();
  }
}
function playMusic(order) {
  musicOrder = order;
  openScene("music",["rain_finale_" + order]);
}
function begin(startWithRain = false) {
  ending = "";
  musicOrder = "";
  document.body.classList.add("in-game");
  $("intro").hidden = true;
  $("play-area").hidden = false;
  if (startWithRain) openScene("rain",["rain_opening","rain_direct_q"]);
  else openScene("water",["opening","splash","water_q"]);
}
function pauseTurn() {
  if (state !== "playing") return;
  state = "paused";
  audio.pause();
  drawPlayback();
}
$("open").addEventListener("click",() => begin());
$("open-rain").addEventListener("click",() => begin(true));
$("skip").addEventListener("click",() => showChoices(true));
$("replay").addEventListener("click",() => {
  $("title").focus({preventScroll:true});
  const cues = stage === "end" ? ["finale_" + ending] : stage === "music" ? ["rain_finale_" + musicOrder] : replayCues[stage];
  startQueue(cues);
});
$("pause").addEventListener("click",() => {
  if (state === "playing") return pauseTurn();
  if (state === "error") return playClip();
  if (state === "paused") requestPlay(generation);
});
document.addEventListener("visibilitychange",() => { if (document.hidden) pauseTurn(); });
window.addEventListener("pagehide",pauseTurn);
