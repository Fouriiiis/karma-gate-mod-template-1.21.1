

// Assembly-CSharp, Version=0.0.0.0, Culture=neutral, PublicKeyToken=null
// SuperStructureProjector.SingleGlyph
using System.Collections.Generic;
using RWCustom;
using UnityEngine;

public class SingleGlyph : Glyph
{
	public int procreate;

	private bool selected;

	public bool inactive;

	public SingleGlyph(SuperStructureProjector projector, IntVector2 pos)
		: base(projector, pos)
	{
		Reset(pos);
	}

	public void Reset(IntVector2 pos)
	{
		life = Random.Range(10, 600);
		if (Random.value < 0.5f)
		{
			procreate = -1;
		}
		else
		{
			procreate = Random.Range(1, life);
		}
		selected = Random.value < 0.02f;
		projector.glyphGrid[pos.x, pos.y] = this;
		counter = 0;
	}

	public override void Update(bool eu)
	{
		if (!inactive)
		{
			base.Update(eu);
			if (counter == procreate || (counter == 10 && Random.value > 1f / 3f && projector.glyphsList.Count < projector.idealGlyphNumber))
			{
				Procreate();
			}
		}
	}

	private void Procreate()
	{
		List<IntVector2> list = new List<IntVector2>();
		for (int i = 0; i < 4; i++)
		{
			IntVector2 item = pos + Custom.fourDirections[i];
			if (item.x >= 0 && item.y >= 0 && item.x < projector.glyphGrid.GetLength(0) && item.y < projector.glyphGrid.GetLength(1) && projector.glyphGrid[item.x, item.y] == null)
			{
				list.Add(item);
			}
		}
		if (list.Count > 0)
		{
			projector.AddSingleGlyphAt(list[Random.Range(0, list.Count)]);
		}
	}

	public override void InitiateSprites(RoomCamera.SpriteLeaser sLeaser, RoomCamera rCam)
	{
		sLeaser.sprites = new FSprite[1];
		sLeaser.sprites[0] = new FSprite("glyphs");
		sLeaser.sprites[0].scaleX = 0.02f;
		sLeaser.sprites[0].scaleY = 1f;
		sLeaser.sprites[0].shader = rCam.room.game.rainWorld.Shaders["GlyphProjection"];
		sLeaser.sprites[0].anchorX = 0f;
		sLeaser.sprites[0].anchorY = 0f;
		sLeaser.sprites[0].color = new Color(selected ? 1f : 0f, 0f, 0f);
		AddToContainer(sLeaser, rCam, null);
	}

	public override void DrawSprites(RoomCamera.SpriteLeaser sLeaser, RoomCamera rCam, float timeStacker, Vector2 camPos)
	{
		for (int i = 0; i < sLeaser.sprites.Length; i++)
		{
			sLeaser.sprites[i].isVisible = projector.visible && !inactive;
		}
		if (projector.visible && !inactive)
		{
			Vector2 vector = projector.GridPos(pos, timeStacker) + rCam.room.cameraPositions[rCam.currentCameraPosition];
			sLeaser.sprites[0].isVisible = true;
			sLeaser.sprites[0].x = vector.x - camPos.x;
			sLeaser.sprites[0].y = vector.y - camPos.y;
			projector.UpdateOffset(timeStacker, rCam);
			if (base.slatedForDeletetion || room != rCam.room)
			{
				sLeaser.CleanSpritesAndRemove();
			}
		}
	}

	public void DeActivate()
	{
		if (!inactive)
		{
			projector.inActiveGlyphsWaitingRoom.Add(this);
			projector.glyphGrid[pos.x, pos.y] = null;
			inactive = true;
		}
	}
}
